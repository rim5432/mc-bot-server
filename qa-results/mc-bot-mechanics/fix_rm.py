import json

with open('qa-results/mc-bot-mechanics/qa-run.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

rm_enhancements = {
    'RM-001': {
        'failure_mode': 'Block.dropResources 收到 ItemStack.EMPTY 作为 TOOL，loot table 的 match_tool 条件永远为 false',
        'business_impact': 'bot 挖掘不掉落正确物品（石头不掉圆石、树叶不掉苹果），无法获取资源，生存模式不可玩',
        'oracle': '手持镐挖石头后地面出现圆石物品实体；徒手挖石头后无圆石掉落',
        'requirement_ids': ['REQ-MCBOTMECHANI-004'],
    },
    'RM-002': {
        'failure_mode': '无 lastSwingTick 冷却追踪，每次 USE rising edge 都执行 hurt，伤害不随冷却时间缩放',
        'business_impact': 'bot 攻击输出远超 vanilla 玩家，战斗难度失衡，PvP/PvE 体验不一致',
        'oracle': '剑连续攻击时，冷却未就绪（<12.5t）的攻击只挥空不造成伤害；满蓄力攻击造成全额伤害',
        'requirement_ids': ['REQ-MCBOTMECHANI-005'],
    },
    'RM-003': {
        'failure_mode': 'BotBodyEntity 无 experienceLevel 字段，MeleeResolver 未调用 setLastHurtByPlayer，击杀怪物不掉经验球',
        'business_impact': 'bot 无法获取经验，铁砧/附魔无法消耗 XP，完整玩家体验断裂',
        'oracle': '击杀僵尸后地面出现 ExperienceOrb 实体；bot 接触后 experienceLevel 提升；harness 可读取 xp 字段',
        'requirement_ids': ['REQ-MCBOTMECHANI-008'],
    },
    'RM-004': {
        'failure_mode': '改动 BotBodyEntity/MeleeResolver/DigExecutor 等核心文件导致已有测试断言失败',
        'business_impact': '已有机制（饥饿、生命、反射、物品栏、合成）回归，整体质量下降',
        'oracle': 'python tool/mcbot_tool.py test 输出 BUILD SUCCESSFUL，539 测试 0 failures',
        'requirement_ids': ['REQ-MCBOTMECHANI-013'],
    },
    'RM-005': {
        'failure_mode': 'Climb movement viability 检查条件错误（如缺少 CLIMBABLE tag 检查或上方可通行检查），路径规划不生成攀爬 edge',
        'business_impact': 'bot 无法通过路径规划上梯子/藤蔓，地形导航能力受限',
        'oracle': '在梯子旁调用 BasicMoves.from(src) 返回的 movement 列表包含 Climb 类型，且 viable=true',
        'requirement_ids': ['REQ-MCBOTMECHANI-001'],
    },
    'RM-006': {
        'failure_mode': 'Efficiency 公式错误（如用 level 而非 level²）或 Haste/Fatigue 乘数叠加顺序错误',
        'business_impact': '挖掘速度与 vanilla 不一致，附魔效果感知偏差，游戏体验失真',
        'oracle': 'Efficiency V 钻石镐挖石头的 perTickProgress 包含 (1+5²)=26 倍乘数；Haste II 额外 +40%',
        'requirement_ids': ['REQ-MCBOTMECHANI-002'],
    },
    'RM-007': {
        'failure_mode': 'sneak 在水中未映射为下潜，或 applyWaterDescent 条件错误（如缺少 isInWater 检查）',
        'business_impact': 'bot 无法在水中下潜，只能上浮，水下导航和探索能力缺失',
        'oracle': 'bot 在水中、非地面、sneak=true 时 y 速度为 -0.10/tick，y 坐标逐 tick 下降',
        'requirement_ids': ['REQ-MCBOTMECHANI-003'],
    },
    'RM-008': {
        'failure_mode': 'isCriticalHit() 缺少 vanilla 条件（如 isInWater、onClimbable、isPassenger 检查），在水中或梯子上错误触发暴击',
        'business_impact': '暴击触发条件与 vanilla 不一致，战斗伤害计算偏差',
        'oracle': 'bot 在水中下落攻击时伤害不乘以 1.5；在地面以上下落且全部条件满足时伤害×1.5',
        'requirement_ids': ['REQ-MCBOTMECHANI-006'],
    },
    'RM-009': {
        'failure_mode': 'maybeBackOffFromEdge override 条件错误或 0.05 步长迭代不收敛，sneak 时仍走过边缘坠落',
        'business_impact': 'bot 在悬崖边 sneak 时仍会坠落，安全移动能力缺失，高地形作业危险',
        'oracle': 'bot sneak 状态走向悬崖边缘时 y 坐标不变，停在边缘方块上；非 sneak 时正常坠落',
        'requirement_ids': ['REQ-MCBOTMECHANI-007'],
    },
    'RM-010': {
        'failure_mode': 'BotPlayerFacade 的 experienceLevel/experienceProgress 字段永远为 0，syncExperience 未被调用或未正确同步 body XP',
        'business_impact': '铁砧/附魔菜单显示无 XP，无法执行需要消耗 XP 的操作，菜单系统不可用',
        'oracle': 'bot 有 5 级经验时打开铁砧，菜单 UI 显示等级 5；铁砧重命名消耗 1 级后 bot 等级变为 4',
        'requirement_ids': ['REQ-MCBOTMECHANI-009'],
    },
}

for rm in data['risk_mechanisms']:
    seq = rm['id'].split('-')[-1]
    key = f'RM-{seq}'
    if key in rm_enhancements:
        for k, v in rm_enhancements[key].items():
            rm[k] = v

with open('qa-results/mc-bot-mechanics/qa-run.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

rm_full = sum(1 for r in data['risk_mechanisms'] if all(r.get(k) for k in ['failure_mode','business_impact','oracle','requirement_ids']))
print(f'Risk mechanisms fully enhanced: {rm_full}/{len(data["risk_mechanisms"])}')
