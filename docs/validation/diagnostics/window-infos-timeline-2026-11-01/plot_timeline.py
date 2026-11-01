"""Render already archived observations; no interpolation or new device sampling."""
import json
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
original = ROOT / 'screenshots/window-infos-keyboard-41305950037-1/enabled'
raw = json.loads((original / 'raw-result.json').read_text())
fixture = json.loads((original / 'fixture.json').read_text())
review = json.loads((HERE / 'history-source-review.json').read_text())
timeline = review['timeline']
show = fixture['showRequestedElapsedMs']
assert show == timeline['fixtureShowElapsedMs']
negative, positive = [], []
for sample in raw['samples']:
    rows = sample['rows']
    if not rows:
        continue
    passed = any(not r['regionEmpty'] and not r['notVisible'] and not r['isClone']
                 and r['frame'][0] < r['frame'][2] and r['frame'][1] < r['frame'][3]
                 for r in rows)
    (positive if passed else negative).append((sample['elapsedRealtimeMs'] - show) / 1000)
assert len(negative) == 21 and positive == [timeline['positiveMinusShowMs'] / 1000]
events = {e['historySequence']: e for e in timeline['selectedTrackerEntries']}
plt.rcParams.update({'svg.fonttype': 'none', 'svg.hashsalt': 'agent-os-ime-history-41305950037', 'font.size': 10})
fig, axes = plt.subplots(2, 1, figsize=(11, 5.7), layout='constrained',
                         gridspec_kw={'height_ratios': [1.3, 1]})
for ax in axes:
    ax.scatter(negative, [2] * len(negative), marker='x', color='#a33d2c', s=40,
               label='Raw candidate with empty region', zorder=4)
    ax.scatter(positive, [2], color='#08735e', s=65, label='Raw positive callback', zorder=5)
    for seq, row, color in [(12, 1, '#4c66a4'), (13, .5, '#7756a4'), (14, 0, '#aa720d')]:
        e = events[seq]
        lo = (e['startElapsedMs'] - show) / 1000
        hi = (e['finishElapsedMsDerived'] - show) / 1000
        ax.plot([lo, hi], [row, row], color=color, linewidth=6, solid_capstyle='butt')
        ax.scatter([lo, hi], [row, row], s=16, color=color, zorder=3)
    ax.set_yticks([0, .5, 1, 2], ['#14 CONTROLS_CHANGED (CANCEL)',
                 '#13 RESET_NEW_CONFIGURATION (CANCEL)', '#12 animation tracking (SUCCESS)', 'Raw callbacks'])
    ax.set_ylim(-.35, 2.4)
    ax.grid(axis='x', color='#d9dce1', linewidth=.6)
    ax.spines[['top', 'right']].set_visible(False)
axes[0].set_xlim(-.1, (raw['times']['freezeElapsedMs'] - show) / 1000 + .1)
axes[0].axvspan(negative[-1], positive[0], color='#f0f1f3', zorder=0)
axes[0].text(4.1, 1.65, '7.558 s without a recorded callback\nNot continuous empty-region evidence',
             ha='center', va='center', fontsize=10)
axes[0].annotate('First Insets visible: +0.207 s', xy=(.207, 2.15), xytext=(1.6, 2.2),
                 arrowprops={'arrowstyle': '-', 'color': '#555'}, fontsize=9)
axes[0].set_title('Existing API 37 B-arm observations — source aee08f197 / run 41305950037', loc='left')
axes[0].set_xlabel('Seconds after the single fixture show request (not the Notes deadline origin)')
axes[1].set_xlim(7.85, 8.23)
axes[1].annotate('First raw positive: +8.135 s', xy=(8.135, 2), xytext=(7.86, 2.25),
                 arrowprops={'arrowstyle': '-', 'color': '#08735e'}, fontsize=10)
axes[1].set_xlabel('Detail near the first positive; temporal order does not establish causation')
fig.savefig(HERE / 'timeline.svg', metadata={'Date': None,
            'Description': 'Existing elapsedRealtime observations only. Cancelled tracking events do not prove UI rollback.'})
# Normalize generated XML whitespace without changing any plotted coordinates.
svg = HERE / 'timeline.svg'
svg.write_text('\n'.join(line.rstrip() for line in svg.read_text().splitlines()) + '\n')
