import io
import zipfile
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import tempfile
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from lan_receiver import Store, ThreadingHTTPServer, handler


class ReceiverTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.temp.name))
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), handler(self.store))
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.base = 'http://127.0.0.1:' + str(self.server.server_port)
        self.packet = dict(run_id='run1', metadata=dict(run_id='run1', experiment_name='실험'),
                           samples=[dict(seq=i, sensor_timestamp_ns=str(100+i),
                                         elapsed_ms=i*10, lux=10+i, accuracy=3) for i in range(3)])

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.store.db.close()
        self.temp.cleanup()

    def post(self, packet):
        request = Request(self.base + '/ingest', data=json.dumps(packet).encode(),
                          headers={'Content-Type': 'application/json'})
        with urlopen(request, timeout=3) as response:
            self.assertEqual(response.status, 200)

    def test_retry_final_boundary_and_restart(self):
        self.post(self.packet)
        self.post(self.packet)  # Simulate lost acknowledgement and retry.
        self.assertEqual(self.store.run('run1')['count'], 3)
        self.packet['samples'] = []
        self.packet['end'] = dict(sample_count=2, run_end_ns='102', reason='manual')
        self.post(self.packet)
        with urlopen(self.base + '/api/run?id=run1') as response:
            result = json.load(response)
        self.assertEqual(result['count'], 2)
        self.assertEqual([s['seq'] for s in result['samples']], [0, 1])
        reopened = Store(Path(self.temp.name))
        try:
            self.assertEqual(reopened.run('run1')['end']['sample_count'], 2)
        finally:
            reopened.db.close()

    def test_bad_packet_does_not_create_run(self):
        self.packet['metadata']['run_id'] = 'wrong'
        with self.assertRaises(HTTPError) as error:
            self.post(self.packet)
        self.assertEqual(error.exception.code, 400)
        self.assertEqual(self.store.runs(), [])

    @staticmethod
    def zip_bytes(index):
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as archive:
            archive.writestr(zipfile.ZipInfo('Light.csv'), 'time,seconds_elapsed,lux\n' + str(index))
            archive.writestr(zipfile.ZipInfo('alignment/result.csv'), 'preserved')
        return output.getvalue()

    def upload_zip(self, index):
        data = self.zip_bytes(index)
        request = Request(self.base + '/archives?name=experiment_' + str(index) + '.zip',
                          data=data, headers={'Content-Type': 'application/zip'})
        try:
            with urlopen(request, timeout=5) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            return error.code, json.load(error)

    def test_archive_capacity_download_and_restart(self):
        for i in range(20):
            self.assertEqual(self.upload_zip(i)[0], 200)
        original = self.store.archives()
        self.assertEqual(len(original), 20)
        self.assertEqual(self.upload_zip(21)[0], 409)
        self.assertEqual(self.store.archives(), original)
        # An exact retry is idempotent even when capacity has been reached.
        self.assertEqual(self.upload_zip(19)[0], 200)
        with urlopen(self.base + '/api/archive?id=' + original[0]['id']) as response:
            self.assertEqual(response.headers['Content-Type'], 'application/zip')
            self.assertEqual(response.read(), self.zip_bytes(19))
        reopened = Store(Path(self.temp.name))
        try:
            self.assertEqual(len(reopened.archives()), 20)
            with self.assertRaises(OverflowError):
                reopened.save_archive('new.zip', self.zip_bytes(22))
        finally:
            reopened.db.close()

    def test_concurrent_uploads_cannot_exceed_capacity(self):
        for i in range(19):
            self.store.save_archive(str(i) + '.zip', self.zip_bytes(i))
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(self.upload_zip, range(30, 34)))
        self.assertEqual(sorted(code for code, _ in results), [200, 409, 409, 409])
        self.assertEqual(len(self.store.archives()), 20)

    def test_invalid_zip_does_not_consume_slot(self):
        request = Request(self.base + '/archives?name=test.zip', data=b'not a zip')
        with self.assertRaises(HTTPError) as error:
            urlopen(request)
        self.assertEqual(error.exception.code, 400)
        self.assertEqual(self.store.archives(), [])

    def test_delete_frees_slot_preserves_other_data_and_persists(self):
        self.post(self.packet)
        for i in range(20):
            self.assertEqual(self.upload_zip(i)[0], 200)
        originals = self.store.archives()
        target = originals[0]['id']
        request = Request(self.base + '/api/archive?id=' + target, method='DELETE',
                          headers={'X-ALS-Confirm': 'delete'})
        with urlopen(request) as response:
            self.assertEqual(response.status, 200)
        self.assertEqual(len(self.store.archives()), 19)
        with self.assertRaises(HTTPError) as error:
            urlopen(self.base + '/api/archive?id=' + target)
        self.assertEqual(error.exception.code, 404)
        for other in originals[1:]:
            self.assertTrue(self.store.archive(other['id'])[1])
        self.assertEqual(self.store.run('run1')['count'], 3)
        reopened = Store(Path(self.temp.name))
        try:
            self.assertEqual(len(reopened.archives()), 19)
        finally:
            reopened.db.close()
        self.assertEqual(self.upload_zip(21)[0], 200)
        self.assertEqual(len(self.store.archives()), 20)
        self.assertEqual(self.upload_zip(22)[0], 409)

    def test_delete_requires_confirmation_and_handles_missing_archive(self):
        _, result = self.upload_zip(1)
        target = result['id']
        with self.assertRaises(HTTPError) as error:
            urlopen(Request(self.base + '/api/archive?id=' + target, method='DELETE'))
        self.assertEqual(error.exception.code, 403)
        self.assertEqual(len(self.store.archives()), 1)
        request = Request(self.base + '/api/archive?id=' + '0' * 64, method='DELETE',
                          headers={'X-ALS-Confirm': 'delete'})
        with self.assertRaises(HTTPError) as error:
            urlopen(request)
        self.assertEqual(error.exception.code, 404)
        self.assertEqual(len(self.store.archives()), 1)


if __name__ == '__main__':
    unittest.main()
