import json

with open('qa-results/mc-bot-mechanics/qa-run.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

# Structured behavior + impact scope for each P0/P1 requirement
enhancements = {
    'REQ-001': {
        'behavior': '前置: bot 位于 CLIMBABLE tag 方块旁且上方可通行 → 触发: BasicMoves.from(src) 枚举 → 规则: Climb movement viable 当且仅当源格相邻攀爬方块且目标格在上方且可通行 → 中间状态: PathingGraph 收录 Climb edge → 最终状态: 路径规划可生成含攀爬的路径 → 可观察: BotPath 包含 climb 类型的 step',
        'impact_scope': 'core/pathing/BasicMoves.java, core/pathing/PathingBehavior.java, api/pathing/BotMove.java'
    },
    'REQ-002': {
        'behavior': '前置: bot 手持工具开始挖掘 → 触发: DigPacing.perTickProgress 计算 → 规则: baseSpeed × efficiencyMultiplier(1+level²) × hasteMultiplier(1+0.2×level) × fatigueMultiplier(0.7^level) × waterMultiplier(AquaAffinity?1:0.2) × airMultiplier(onGround?1:0.2) → 中间状态: progress 累积 → 最终状态: progress≥1.0 时方块破坏 → 可观察: 挖掘耗时与 vanilla 公式对齐',
        'impact_scope': 'core/actor/DigPacing.java, adapter/DigExecutor.java, api/actor/DigIntent.java'
    },
    'REQ-003': {
        'behavior': '前置: bot 在水中且 waypoint 低于当前 floor → 触发: PathingBehavior 提交 Move(sneak=true) → 规则: BotBodyEntity.applyWaterDescent 在 isInWater && !onGround && isShiftKeyDown 时设置 y 速度 -0.10/tick → 中间状态: bot 缓慢下潜 → 最终状态: 到达目标深度 → 可观察: y 坐标随时间下降',
        'impact_scope': 'core/pathing/BasicMoves.java, core/pathing/PathingBehavior.java, adapter/entity/BotBodyEntity.java'
    },
    'REQ-004': {
        'behavior': '前置: DigExecutor 挖掘进度达到 1.0 → 触发: 手动 break 序列 → 规则: 捕获 state+blockEntity+fluid → emit 2001 粒子 → setBlock(fluid legacy) → Block.dropResources(state, level, pos, be, body, held) 其中 TOOL=held → 中间状态: 掉落物实体生成 + XP 球生成 → 最终状态: GroundPickup 拾取掉落物 → 可观察: 镐挖石头掉圆石、徒手不掉、剪刀剪树叶掉树叶',
        'impact_scope': 'adapter/DigExecutor.java, adapter/entity/GroundPickup.java, core/actor/DigPacing.java'
    },
    'REQ-005': {
        'behavior': '前置: MeleeResolver 收到 USE rising edge → 触发: onUsePress 检查冷却 → 规则: cooldown = 1.0/ATTACK_SPEED×20 ticks; 当前tick - lastSwingTick < cooldown 时只 swing 不 hurt; ≥ cooldown 时执行 hurt 并更新 lastSwingTick → 中间状态: 冷却计时 → 最终状态: 满蓄力攻击造成全额伤害 → 可观察: 连续点击只有第一击满伤害',
        'impact_scope': 'adapter/MeleeResolver.java, api/actor/Intent.java'
    },
    'REQ-006': {
        'behavior': '前置: MeleeResolver 执行攻击 → 触发: isCriticalHit() 检查 → 规则: fallDistance>0 && !onGround && !isInWater && !hasEffect(BLINDNESS) && !onClimbable && !isPassenger && !isSprinting → 中间状态: damage ×= 1.5 → 最终状态: 目标受到暴击伤害 + 2002 粒子 → 可观察: 下落攻击伤害为普通攻击 1.5 倍',
        'impact_scope': 'adapter/MeleeResolver.java'
    },
    'REQ-007': {
        'behavior': '前置: BotBodyEntity.move 被调用且 isShiftKeyDown → 触发: maybeBackOffFromEdge(vec3, moverType) → 规则: moverType==SELF && y≤0 && isShiftKeyDown && isBotAboveGround 时, 0.05 步长迭代削减 x/z 直到脚下有地面 → 中间状态: 速度向量被逐步削减 → 最终状态: bot 停在边缘不坠落 → 可观察: sneak 走到悬崖边 y 坐标不变',
        'impact_scope': 'adapter/entity/BotBodyEntity.java'
    },
    'REQ-008': {
        'behavior': '前置: BotBodyEntity 存在且有 XP 字段 → 触发: 击杀怪物 / 拾取经验球 / 消耗 XP → 规则: MeleeResolver 攻击时 setLastHurtByPlayer(facade) → 怪物 die() 检查 lastHurtByPlayerTime>0 生成 ExperienceOrb → tickXpPickup 每 tick 吸收范围内 orb → experienceLevel 按 vanilla 公式提升 → BotState 暴露 experienceLevel → 可观察: 击杀僵尸后经验球出现、拾取后等级提升、harness 可读取 xp',
        'impact_scope': 'adapter/entity/BotBodyEntity.java, adapter/BotPlayerFacade.java, adapter/MeleeResolver.java, api/state/BotState.java, api/state/BotStateJson.java'
    },
    'REQ-009': {
        'behavior': '前置: harness 请求打开菜单 → 触发: MenuOpener.open(blockPos/entityId) → 规则: generic MenuProvider 回退路径打开铁砧/酿造台/附魔台 → openEntity 打开村民交易 → facade.syncExperience 在菜单操作前同步 body XP 到 facade 字段 → BindingMenu.setAnvilName 调 AnvilMenu.setItemName → clickButton 选附魔 → 中间状态: 菜单打开 + XP 同步 → 最终状态: 菜单操作执行 + XP 消耗 → 可观察: 铁砧显示正确 XP 等级、重命名生效、附魔选项可选',
        'impact_scope': 'adapter/MenuOpener.java, adapter/MenuSlotLayouts.java, adapter/BindingMenu.java, adapter/BotPlayerFacade.java'
    },
    'REQ-013': {
        'behavior': '前置: 代码改动完成 → 触发: python tool/mcbot_tool.py test → 规则: compileJava → checkstyleMain → spotlessCheck → test (539 个 JUnit) → 中间状态: 编译+静态检查+测试执行 → 最终状态: BUILD SUCCESSFUL 且 0 failures → 可观察: 测试报告全绿',
        'impact_scope': '全量 src/main/java + src/test/java'
    },
}

for req in data['requirements']:
    rid = req['id']
    seq = rid.split('-')[-1]  # last 3 digits
    key = f'REQ-{seq}'
    if key in enhancements:
        req['behavior'] = enhancements[key]['behavior']
        req['impact_scope'] = enhancements[key]['impact_scope']

# Also add to P2 requirements (simpler)
p2_enhancements = {
    'REQ-010': {
        'behavior': '前置: 方块破坏完成 → 触发: held.getItem().mineBlock(held, level, state, pos, body) → 规则: DiggerItem 伤1点、SwordItem 伤2点、ShearsItem 伤1点、基类Item不伤; gate 在 destroySpeed!=0; Unbreaking/Mending 在 hurtAndBreak 内自动生效 → 可观察: 镐挖100块后耐久减少',
        'impact_scope': 'adapter/DigExecutor.java'
    },
    'REQ-011': {
        'behavior': '前置: 方块破坏完成 → 触发: body.getFoodData().addExhaustion(0.005F) → 规则: mirror vanilla Block.playerDestroy 的 0.005/block; exhaustion 累积到 >4.0 时消耗 saturation, saturation 耗尽后消耗 foodLevel → 可观察: 连续挖掘后饥饿值下降',
        'impact_scope': 'adapter/DigExecutor.java, adapter/entity/HungerTicker.java'
    },
    'REQ-012': {
        'behavior': '前置: MeleeResolver 主攻击命中 → 触发: 横扫条件检查 → 规则: !crit && !isSprinting && onGround && 手持剑(SWORD_SWEEP) → 横扫伤害=1.0+SweepingEdge比率×主伤害; 击退0.4; getSweepHitBox(facade,target) 范围内 LivingEntity, 排除自己/主目标/同盟; 播放 PLAYER_ATTACK_SWEEP → 可观察: 多怪站位时附近敌人受到横扫伤害',
        'impact_scope': 'adapter/MeleeResolver.java'
    },
}

for req in data['requirements']:
    rid = req['id']
    seq = rid.split('-')[-1]
    key = f'REQ-{seq}'
    if key in p2_enhancements:
        req['behavior'] = p2_enhancements[key]['behavior']
        req['impact_scope'] = p2_enhancements[key]['impact_scope']

with open('qa-results/mc-bot-mechanics/qa-run.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

enhanced = sum(1 for r in data['requirements'] if 'behavior' in r)
print(f'Enhanced {enhanced}/{len(data["requirements"])} requirements with behavior + impact_scope')
