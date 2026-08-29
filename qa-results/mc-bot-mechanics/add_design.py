import json

with open('qa-results/mc-bot-mechanics/qa-run.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

data['risk_mechanisms'] = [
    {'id': 'RM-001', 'requirement_id': 'REQ-004', 'title': 'match_tool 条件不触发', 'mechanism': 'destroyBlock 传 EMPTY tool 导致 loot table match_tool 永远 false', 'severity': 'P0', 'detection': '挖石头验证掉落物为圆石'},
    {'id': 'RM-002', 'requirement_id': 'REQ-005', 'title': '攻击冷却失效', 'mechanism': '无冷却追踪导致每次点击都满伤害', 'severity': 'P0', 'detection': '连续攻击验证伤害随冷却变化'},
    {'id': 'RM-003', 'requirement_id': 'REQ-008', 'title': 'XP 系统断裂', 'mechanism': 'BotBodyEntity 无 XP 字段，击杀不掉经验球', 'severity': 'P0', 'detection': '击杀僵尸验证经验球+等级提升'},
    {'id': 'RM-004', 'requirement_id': 'REQ-013', 'title': '回归破坏', 'mechanism': '改动核心文件导致已有测试失败', 'severity': 'P0', 'detection': '全量 test 套件'},
    {'id': 'RM-005', 'requirement_id': 'REQ-001', 'title': '攀爬不可达', 'mechanism': 'Climb viability 检查错误', 'severity': 'P1', 'detection': '梯子旁验证生成 Climb edge'},
    {'id': 'RM-006', 'requirement_id': 'REQ-002', 'title': '附魔乘数错误', 'mechanism': 'Efficiency 公式或 Haste/Fatigue 叠加顺序错误', 'severity': 'P1', 'detection': 'Efficiency V 验证速度'},
    {'id': 'RM-007', 'requirement_id': 'REQ-003', 'title': '下潜不生效', 'mechanism': 'sneak 在水中未映射为下潜', 'severity': 'P1', 'detection': '水中 sneak 验证 y 速度为负'},
    {'id': 'RM-008', 'requirement_id': 'REQ-006', 'title': '暴击条件遗漏', 'mechanism': '缺少 isInWater 或 onClimbable 检查', 'severity': 'P1', 'detection': '水中下落验证不暴击'},
    {'id': 'RM-009', 'requirement_id': 'REQ-007', 'title': '边缘回退失效', 'mechanism': 'maybeBackOffFromEdge 条件错误或迭代不收敛', 'severity': 'P1', 'detection': 'sneak 悬崖边验证不坠落'},
    {'id': 'RM-010', 'requirement_id': 'REQ-009', 'title': '菜单 XP 不同步', 'mechanism': 'facade XP 字段永远为 0', 'severity': 'P1', 'detection': '铁砧验证 XP 显示正确'},
]

cases = []
tc = 1
def add(req, title, pri, desc):
    global tc
    cases.append({
        'id': f'TC-{tc:03d}', 'requirement_id': req, 'title': title,
        'priority': pri, 'status': 'not_executed', 'validation_scope': 'formal',
        'description': desc
    })
    tc += 1

add('REQ-001', '梯子上生成 Climb edge', 'P1', 'CLIMBABLE tag 方块旁 BasicMoves 生成 Climb movement')
add('REQ-001', '非攀爬方块不生成 Climb', 'P2', '普通方块旁不生成 Climb movement')
add('REQ-002', 'Efficiency V 速度加成', 'P1', 'Efficiency V 包含 (1+5^2)=26 倍乘数')
add('REQ-002', 'Haste II 叠加', 'P2', 'Haste II 额外 +40%')
add('REQ-002', 'Mining Fatigue III 减速', 'P2', 'Fatigue III 减至 0.343 倍')
add('REQ-003', '水中 sneak 下潜', 'P1', '水中+非地面+sneak 时 y 速度 -0.10/tick')
add('REQ-003', 'SwimDown viability', 'P1', '源/目标均液体且可通行时 viable')
add('REQ-004', '镐挖石头掉圆石', 'P0', 'TOOL=镐时 match_tool 触发掉圆石')
add('REQ-004', '徒手挖石头不掉圆石', 'P0', '徒手时 match_tool 不满足')
add('REQ-004', '剪刀剪树叶掉树叶', 'P1', 'TOOL=剪刀时 match_tool shears')
add('REQ-004', '挖掘生成 XP 球', 'P1', '煤矿/红石矿生成 ExperienceOrb')
add('REQ-005', '剑冷却 12.5 tick', 'P0', 'ATTACK_SPEED=1.6 cooldown=12.5t，未就绪只挥空')
add('REQ-005', 'rising edge 重置冷却', 'P1', '每次 USE rising edge 重置 lastSwingTick')
add('REQ-006', '下落攻击暴击 1.5x', 'P1', '全部暴击条件满足时伤害×1.5+2002粒子')
add('REQ-006', '水中不暴击', 'P2', 'isInWater=true 时不暴击')
add('REQ-007', 'sneak 悬崖边不坠落', 'P1', 'maybeBackOffFromEdge 削减速度直到脚下有地面')
add('REQ-007', '非 sneak 正常坠落', 'P2', '非 sneak 时不干预')
add('REQ-008', '击杀僵尸掉经验球', 'P0', 'setLastHurtByPlayer 使 die() 生成 ExperienceOrb')
add('REQ-008', '经验球拾取+等级提升', 'P0', 'tickXpPickup 吸收+等级提升')
add('REQ-008', 'XP 暴露 harness', 'P1', 'BotState 第13组件+JSON xp key')
add('REQ-008', 'XP 公式 vanilla 对齐', 'P1', '0-15:7+2l, 15-30:37+5(l-15), 30+:112+9(l-30)')
add('REQ-009', '铁砧菜单+XP 同步', 'P1', 'MenuOpener 打开+facade.syncExperience')
add('REQ-009', '铁砧重命名', 'P2', 'setAnvilName 调 AnvilMenu.setItemName')
add('REQ-009', '附魔台+按钮', 'P1', 'MenuOpener 打开+clickButton 选附魔')
add('REQ-009', '村民交易打开', 'P2', 'openEntity 打开 MerchantMenu')
add('REQ-010', '镐挖方块伤1耐久', 'P2', 'DiggerItem.mineBlock 伤1点')
add('REQ-010', '剑挖方块伤2耐久', 'P2', 'SwordItem.mineBlock 伤2点')
add('REQ-011', '每方块0.005 exhaustion', 'P2', 'addExhaustion(0.005F) 连续挖掘饥饿下降')
add('REQ-012', '剑满蓄力非疾跑横扫', 'P2', '横扫 AoE 伤害=1.0+SweepingEdge比率×主伤害')
add('REQ-012', '疾跑不横扫', 'P2', 'isSprinting 时改为击退加成')
add('REQ-013', '全量539测试通过', 'P0', 'test 全绿+编译+checkstyle+spotless')
add('REQ-013', '边界契约 marker 完整', 'P1', 'BoundaryContractMarkerTest 通过')

data['cases'] = cases
data['coverage']['requirement_total'] = len(data['requirements'])
data['coverage']['case_total'] = len(cases)
data['coverage']['p0_requirement_total'] = len([r for r in data['requirements'] if r.get('risk') == 'P0'])

with open('qa-results/mc-bot-mechanics/qa-run.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
print(f'Added {len(data["risk_mechanisms"])} risk mechanisms, {len(cases)} test cases')
