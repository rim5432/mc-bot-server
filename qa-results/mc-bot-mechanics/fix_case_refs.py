import json

with open('qa-results/mc-bot-mechanics/qa-run.json', 'r', encoding='utf-8') as f:
    d = json.load(f)

req_ids = {r['id'].split('-')[-1]: r['id'] for r in d['requirements']}
for c in d['cases']:
    old = c.get('requirement_id', '')
    seq = old.split('-')[-1] if old else ''
    if seq in req_ids:
        c['requirement_id'] = req_ids[seq]
        c['requirement_ids'] = [req_ids[seq]]
    if c.get('priority') == 'P0':
        title = c.get('title', '')
        c['release_blocking_reason'] = title + ' 失败直接阻断核心机制可用性，影响完整玩家体验'

with open('qa-results/mc-bot-mechanics/qa-run.json', 'w', encoding='utf-8') as f:
    json.dump(d, f, ensure_ascii=False, indent=2)

p0_ok = sum(1 for c in d['cases'] if c.get('priority') == 'P0' and c.get('release_blocking_reason'))
rid_ok = sum(1 for c in d['cases'] if c.get('requirement_id', '').startswith('REQ-MCBOTMECHANI'))
print(f'P0 release_blocking_reason: {p0_ok}; normalized requirement_ids: {rid_ok}/{len(d["cases"])}')
