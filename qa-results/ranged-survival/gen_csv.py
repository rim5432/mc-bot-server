import json
import csv

with open('qa-results/ranged-survival/qa-run.json', 'r', encoding='utf-8') as f:
    d = json.load(f)

req_map = {r['id']: r['title'] for r in d['requirements']}
rm_map = {rm['id']: rm['title'] for rm in d['risk_mechanisms']}

rows = []
for c in d['cases']:
    req_title = req_map.get(c.get('requirement_id', ''), '')
    rm_titles = '; '.join(rm_map.get(rid, rid) for rid in c.get('risk_mechanism_ids', []))
    steps = ' | '.join(c.get('steps', []))
    rows.append({
        '用例ID': c['id'],
        '需求': req_title,
        '用例标题': c['title'],
        '优先级': c['priority'],
        '模块': c.get('module', ''),
        '类型': c.get('type', ''),
        '描述': c.get('description', ''),
        '步骤': steps,
        '预期结果': c.get('expected_result', ''),
        '关联风险机制': rm_titles,
        '测试数据': c.get('test_data', ''),
        '状态': c.get('status', 'not_executed'),
        '阻断原因': c.get('release_blocking_reason', ''),
    })

csv_path = 'qa-results/ranged-survival/远程-生存能力面 QA 梳理-QA测试用例与追踪.csv'
with open(csv_path, 'w', encoding='utf-8-sig', newline='') as f:
    writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
    writer.writeheader()
    writer.writerows(rows)

print(f'CSV written: {len(rows)} cases to {csv_path}')
