import unittest
from align_recording import VideoClock, align_rows


def mark(t, p):
    return {'event': 'VIDEO_POSITION', 'elapsed_realtime_ns': str(t), 'video_position_ms': str(p)}


class AlignmentTests(unittest.TestCase):
    def test_clock_offsets_have_correct_sign_and_lux_stays_raw(self):
        for player_start, expected in [(0, -10.0), (100, 90.0)]:
            clock = VideoClock([mark(10_000_000, player_start), mark(30_000_000, player_start + 20)])
            rows, removed, _ = align_rows([
                {'sensor_timestamp_ns': '20000000', 'lux': '177.0'}], clock, 0, 40_000_000, [])
            self.assertEqual(expected, float(rows[0]['time_correction_ms']))
            self.assertEqual('177.0', rows[0]['lux'])
            self.assertEqual('20000000', rows[0]['sensor_timestamp_ns'])
            self.assertEqual(0, removed)

    def test_no_extrapolation_or_interpolation_across_gap_reverse_stall(self):
        for points, timestamp, reason in [
            ([mark(0, 0), mark(200_000_000, 200)], 50_000_000, 'POSITION_GAP_TOO_LONG'),
            ([mark(0, 20), mark(20_000_000, 0)], 10_000_000, 'POSITION_REVERSED'),
            ([mark(0, 0), mark(20_000_000, 0)], 10_000_000, 'POSITION_NOT_ADVANCING'),
            ([mark(10, 0), mark(20, 1)], 5, 'OUTSIDE_POSITION_LOG'),
        ]:
            position, status, _ = VideoClock(points).at(timestamp)
            self.assertIsNone(position)
            self.assertEqual(reason, status)

    def test_end_boundary_removed_and_first_frame_guard_applied(self):
        clock = VideoClock([mark(100, 0), mark(200, 1)], first_frame_ns=150)
        rows, removed, _ = align_rows([
            {'sensor_timestamp_ns': str(t), 'lux': '1.0'} for t in [99, 100, 149, 150, 199, 200, 201]
        ], clock, 100, 200, [])
        self.assertEqual(3, removed)
        self.assertEqual('BEFORE_FIRST_FRAME_INFO', rows[1]['alignment_status'])
        self.assertEqual('ESTIMATED_PLAYER_TIME', rows[2]['alignment_status'])


if __name__ == '__main__':
    unittest.main()
