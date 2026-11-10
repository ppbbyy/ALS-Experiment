"""Align real sensor samples to logged MediaPlayer position; never alter input files.

Usage: python tools/align_recording.py PATH_TO_RECORDING [PATH_TO_RECORDING ...]
Outputs live in each recording's alignment/ directory. No resampling is performed.
"""
from __future__ import annotations

import argparse
from bisect import bisect_right
import csv
import hashlib
import json
from pathlib import Path
import statistics

MAX_GAP_NS = 100_000_000
EXTRA_COLUMNS = [
    'aligned_video_time_ms', 'time_correction_ms', 'alignment_status',
    'position_bracket_span_ms', 'aligned_pattern_id', 'aligned_segment_type',
    'aligned_ms_to_transition', 'aligned_transition_flag',
]


def read_rows(path):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream)
        return list(reader.fieldnames or []), list(reader)


class VideoClock:
    def __init__(self, marks, first_frame_ns=None):
        self.first_frame_ns = first_frame_ns
        self.points = sorted(
            (int(m['elapsed_realtime_ns']), int(m['video_position_ms']))
            for m in marks if m['event'] == 'VIDEO_POSITION'
            and int(m['video_position_ms']) >= 0
        )
        self.times = [t for t, _ in self.points]
        if len(set(self.times)) != len(self.times):
            raise ValueError('Duplicate VIDEO_POSITION timestamps; cannot interpolate reliably')

    def at(self, timestamp):
        if self.first_frame_ns is not None and timestamp < self.first_frame_ns:
            return None, 'BEFORE_FIRST_FRAME_INFO', None
        if len(self.times) < 2 or timestamp < self.times[0] or timestamp > self.times[-1]:
            return None, 'OUTSIDE_POSITION_LOG', None
        index = min(bisect_right(self.times, timestamp) - 1, len(self.times) - 2)
        a, pa = self.points[index]
        b, pb = self.points[index + 1]
        gap = b - a
        span = gap / 1e6
        if gap > MAX_GAP_NS:
            return None, 'POSITION_GAP_TOO_LONG', span
        if pb < pa:
            return None, 'POSITION_REVERSED', span
        if pb == pa:
            return None, 'POSITION_NOT_ADVANCING', span
        return pa + (pb - pa) * ((timestamp - a) / gap), 'ESTIMATED_PLAYER_TIME', span


def pattern_at(position, schedule):
    if position is None:
        return ['', '', '', 'NO_POSITION_ESTIMATE']
    for segment in schedule:
        start, end = float(segment['start_ms']), float(segment['end_ms'])
        if start <= position < end:
            distance = min(position - start, end - position)
            return [segment['pattern_id'], segment['segment_type'], f'{distance:.6f}',
                    'NEAR_TRANSITION' if distance <= 50 else 'AWAY_FROM_TRANSITION']
    return ['', '', '', 'NO_CONFIRMED_SCHEDULE']


def align_rows(rows, clock, begin, end, schedule):
    output, discarded, statuses = [], 0, {}
    for row in rows:
        timestamp = int(row['sensor_timestamp_ns'])
        if timestamp < begin or timestamp >= end:
            discarded += 1
            continue
        position, status, span = clock.at(timestamp)
        elapsed = (timestamp - begin) / 1e6
        correction = None if position is None else position - elapsed
        label = pattern_at(position, schedule)
        statuses[status] = statuses.get(status, 0) + 1
        output.append({**row,
            'aligned_video_time_ms': '' if position is None else f'{position:.6f}',
            'time_correction_ms': '' if correction is None else f'{correction:.6f}',
            'alignment_status': status,
            'position_bracket_span_ms': '' if span is None else f'{span:.6f}',
            **dict(zip(EXTRA_COLUMNS[4:], label)),
        })
    return output, discarded, statuses


def process(folder):
    folder = Path(folder).resolve()
    metadata = dict(line.split('=', 1) for line in (folder / 'run_info.txt').read_text(
        encoding='utf-8-sig').splitlines() if '=' in line)
    begin, end = int(metadata['actual_sensor_log_start_ns']), int(metadata['run_end_ns'])
    if end <= begin:
        raise ValueError('Invalid recording window')
    _, marks = read_rows(folder / 'video_timeline.csv')
    first = metadata.get('first_frame_info_callback_ns', 'UNAVAILABLE')
    clock = VideoClock(marks, int(first) if first.isdecimal() else None)
    schedule = []
    schedule_file = folder / 'pattern_schedule.csv'
    if metadata.get('pattern_schedule', '').startswith('USER_') and schedule_file.exists():
        _, schedule = read_rows(schedule_file)
    output_folder = folder / 'alignment'
    output_folder.mkdir(exist_ok=True)
    report = {
        'run_id': metadata.get('run_id', folder.name),
        'method': 'PIECEWISE_LINEAR_INTERPOLATION_OF_LOGGED_MEDIAPLAYER_POSITION',
        'equation': 'aligned_video_time_ms = (sensor_timestamp_ns - actual_sensor_log_start_ns) / 1e6 + time_correction_ms',
        'lux_values_modified': False, 'sensor_timestamps_modified': False,
        'resampled': False, 'optical_onset_calibrated': False,
        'maximum_position_gap_ms': MAX_GAP_NS / 1e6,
        'notes': [
            'Align runs by aligned_video_time_ms; sensor clocks have different origins across runs.',
            'Correction is per sample. Do not substitute one fixed correction across recordings.',
            'Position-log span is not an error bound. NEAR_TRANSITION is only a caution flag.',
            'This reuses the app position clock; it does not independently validate or improve its optical accuracy.',
            'No extrapolation or fabricated sensor values. Blank aligned times are invalid for alignment.',
            'First-frame info may be unavailable after warm-up; in that case it cannot gate pre-display samples.',
            'Label numbering and content are user-confirmed schedule assumptions.',
        ], 'sources': {}, 'outputs': {},
    }
    inputs = [folder / 'run_info.txt', folder / 'video_timeline.csv']
    if schedule_file.exists():
        inputs.append(schedule_file)
    for name in ['als_lux.csv', 'vendor_raw.csv']:
        source = folder / name
        if not source.exists():
            continue
        inputs.append(source)
        fields, rows = read_rows(source)
        if set(fields) & set(EXTRA_COLUMNS):
            raise ValueError('Input already contains alignment columns')
        aligned, discarded, statuses = align_rows(rows, clock, begin, end, schedule)
        target = output_folder / name.replace('.csv', '_aligned.csv')
        with target.open('w', encoding='utf-8', newline='') as stream:
            writer = csv.DictWriter(stream, fieldnames=fields + EXTRA_COLUMNS)
            writer.writeheader()
            writer.writerows(aligned)
        corrections = [float(r['time_correction_ms']) for r in aligned if r['time_correction_ms']]
        report['outputs'][target.name] = {
            'input_rows': len(rows), 'output_rows': len(aligned),
            'outside_recording_window_removed': discarded, 'alignment_status_counts': statuses,
            'median_time_correction_ms': statistics.median(corrections) if corrections else None,
            'minimum_time_correction_ms': min(corrections) if corrections else None,
            'maximum_time_correction_ms': max(corrections) if corrections else None,
        }
    report['sources'] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}
    (output_folder / 'alignment_metadata.json').write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'run_id': report['run_id'], 'outputs': report['outputs']}, ensure_ascii=False))
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recordings', nargs='+', type=Path)
    args = parser.parse_args()
    for recording in args.recordings:
        process(recording)
