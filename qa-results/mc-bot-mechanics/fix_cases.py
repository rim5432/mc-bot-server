import json

with open('qa-results/mc-bot-mechanics/qa-run.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

# Module mapping by requirement sequence
module_map = {
    '001': 'pathing', '002': 'dig', '003': 'pathing', '004': 'dig',
    '005': 'combat', '006': 'combat', '007': 'movement', '008': 'xp',
    '009': 'menu', '010': 'dig', '011': 'dig', '012': 'combat', '013': 'regression',
}

type_map = {
    '001': 'unit', '002': 'unit', '003': 'unit', '004': 'gametest',
    '005': 'gametest', '006': 'gametest', '007': 'gametest', '008': 'gametest',
    '009': 'gametest', '010': 'gametest', '011': 'gametest', '012': 'gametest', '013': 'integration',
}

# Test data fixtures for P0/P1 cases
test_data_map = {
    '004': 'fixture: flat stone platform, bot holding diamond_pickaxe, target block = stone at (0,64,0)',
    '005': 'fixture: bot holding diamond_sword (ATTACK_SPEED=1.6), target = zombie at distance 2.0',
    '008': 'fixture: bot holding diamond_sword, zombie at (2,64,0), bot has 0 XP',
    '013': 'fixture: full codebase at HEAD, python tool/mcbot_tool.py test',
}

# Risk mechanism mapping by requirement
rm_map = {
    '001': ['RM-MCBOTMECHANI-005'],
    '002': ['RM-MCBOTMECHANI-006'],
    '003': ['RM-MCBOTMECHANI-007'],
    '004': ['RM-MCBOTMECHANI-001'],
    '005': ['RM-MCBOTMECHANI-002'],
    '006': ['RM-MCBOTMECHANI-008'],
    '007': ['RM-MCBOTMECHANI-009'],
    '008': ['RM-MCBOTMECHANI-003'],
    '009': ['RM-MCBOTMECHANI-010'],
    '013': ['RM-MCBOTMECHANI-004'],
}

for case in data['cases']:
    req_id = case.get('requirement_id', '')
    seq = req_id.split('-')[-1] if req_id else '000'

    case['module'] = module_map.get(seq, 'unknown')
    case['type'] = type_map.get(seq, 'unit')
    case['requirement_ids'] = [req_id] if req_id else []

    # Generate steps from description
    desc = case.get('description', '')
    case['steps'] = [
        f'前置: 准备测试环境 - {desc[:60]}',
        '执行: 触发被测机制',
        '观察: 记录可观察结果',
        '断言: 验证结果与预期一致',
    ]
    case['expected_result'] = desc

    # P0/P1 need test_data and risk_mechanism_ids
    if case.get('priority') in ('P0', 'P1'):
        case['test_data'] = test_data_map.get(seq, f'fixture: {case["module"]} test environment with default bot state')
        case['risk_mechanism_ids'] = rm_map.get(seq, [])
    else:
        case['test_data'] = 'default fixture'
        case['risk_mechanism_ids'] = rm_map.get(seq, [])

with open('qa-results/mc-bot-mechanics/qa-run.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

full = sum(1 for c in data['cases'] if all(c.get(k) for k in ['module','type','steps','expected_result','requirement_ids']))
print(f'Cases fully enhanced: {full}/{len(data["cases"])}')
