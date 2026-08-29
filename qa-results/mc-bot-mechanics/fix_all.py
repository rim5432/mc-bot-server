import json

with open('qa-results/mc-bot-mechanics/qa-run.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

# behavior as structured dict with 7 required fields
behavior_dicts = {
    '001': {
        'actor': 'BasicMoves (pathing layer)',
        'precondition': 'bot 相邻 CLIMBABLE tag 方块，上方目标格可通行',
        'trigger': 'PathingGraph 枚举 BasicMoves.from(src)',
        'rule': 'Climb movement viable 当且仅当源格相邻攀爬方块且目标格在正上方且可通行',
        'state_change': 'PathingGraph 收录 Climb edge，路径规划可生成含攀爬的路径',
        'observable_result': 'BotPath 包含 climb 类型的 step，bot 可沿梯子上升',
        'failure_behavior': 'viability 检查失败时不生成 Climb edge，bot 无法通过路径规划上梯子',
    },
    '002': {
        'actor': 'DigPacing (core actor layer)',
        'precondition': 'bot 手持工具开始挖掘，目标方块可破坏',
        'trigger': 'DigExecutor.onTick 调用 DigPacing.perTickProgress',
        'rule': 'progress = baseSpeed × efficiency(1+level²) × haste(1+0.2×level) × fatigue(0.7^level) × water(AquaAffinity?1:0.2) × air(onGround?1:0.2)',
        'state_change': 'progress 逐 tick 累积，达到 1.0 时方块破坏',
        'observable_result': '挖掘耗时与 vanilla 公式对齐，Efficiency V 钻石镐挖石头显著加速',
        'failure_behavior': '乘数缺失或顺序错误时挖掘速度偏离 vanilla，可能过快或过慢',
    },
    '003': {
        'actor': 'BotBodyEntity (adapter layer)',
        'precondition': 'bot 在水中，非地面，sneak=true',
        'trigger': 'PathingBehavior 在 waypoint 低于 floor 时提交 Move(sneak=true)',
        'rule': 'applyWaterDescent 在 isInWater && !onGround && isShiftKeyDown 时设置 y 速度 -0.10/tick',
        'state_change': 'bot y 坐标逐 tick 下降，缓慢下潜',
        'observable_result': '水中 sneak 时 y 速度为负，bot 可下潜到目标深度',
        'failure_behavior': '条件不满足时不施加下潜，bot 保持漂浮或上升',
    },
    '004': {
        'actor': 'DigExecutor (adapter layer)',
        'precondition': '挖掘进度达到 1.0，手持物品非空',
        'trigger': 'DigExecutor.onTick 检测 progress >= 1.0',
        'rule': '手动 break 序列：捕获 state+be+fluid → emit 2001 → setBlock(fluid legacy) → Block.dropResources(state, level, pos, be, body, held) TOOL=held',
        'state_change': '方块被移除，掉落物实体和 XP 球生成，GroundPickup 后续拾取',
        'observable_result': '镐挖石头掉圆石、徒手不掉、剪刀剪树叶掉树叶、煤矿掉经验球',
        'failure_behavior': 'TOOL 上下文错误时 match_tool 条件不触发，掉落物与 vanilla 不一致',
    },
    '005': {
        'actor': 'MeleeResolver (adapter layer)',
        'precondition': 'bot 手持武器，目标在攻击范围内',
        'trigger': 'USE intent rising edge 到达 MeleeResolver.onUsePress',
        'rule': 'cooldown = 1.0/ATTACK_SPEED×20 ticks; currentTick - lastSwingTick < cooldown 时只 swing 不 hurt; ≥ cooldown 时 hurt 并更新 lastSwingTick',
        'state_change': 'lastSwingTick 更新，目标受到伤害或仅挥空',
        'observable_result': '剑冷却 12.5t，连续点击只有满蓄力攻击造成全额伤害',
        'failure_behavior': '冷却追踪缺失时每次点击都满伤害，与 vanilla 狂点行为不一致',
    },
    '006': {
        'actor': 'MeleeResolver (adapter layer)',
        'precondition': 'bot 正在攻击目标，伤害计算阶段',
        'trigger': 'performMeleeAttack 调用 isCriticalHit()',
        'rule': 'fallDistance>0 && !onGround && !isInWater && !hasEffect(BLINDNESS) && !onClimbable && !isPassenger && !isSprinting',
        'state_change': '满足时 damage ×= 1.5，播放 2002 暴击粒子',
        'observable_result': '下落攻击伤害为普通攻击 1.5 倍，水中/梯子上不暴击',
        'failure_behavior': '条件遗漏时可能在水中或梯子上错误触发暴击',
    },
    '007': {
        'actor': 'BotBodyEntity (adapter layer)',
        'precondition': 'bot isShiftKeyDown，moverType=SELF，y 速度 ≤ 0',
        'trigger': 'BotBodyEntity.move 调用 maybeBackOffFromEdge(vec3, moverType)',
        'rule': '条件满足时 0.05 步长迭代削减 x/z 速度分量直到 isBotAboveGround 检测脚下有地面',
        'state_change': '速度向量被逐步削减，bot 停在边缘不坠落',
        'observable_result': 'sneak 走到悬崖边 y 坐标不变，非 sneak 时正常坠落',
        'failure_behavior': '迭代不收敛或条件错误时 sneak 仍可能坠落',
    },
    '008': {
        'actor': 'BotBodyEntity + MeleeResolver + BotPlayerFacade (adapter layer)',
        'precondition': 'bot 存在，有 experienceLevel/experienceProgress/totalExperience 字段',
        'trigger': '击杀怪物 / 拾取经验球 / 菜单消耗 XP',
        'rule': 'MeleeResolver 攻击时 setLastHurtByPlayer(facade) → 怪物 die() 检查 lastHurtByPlayerTime>0 生成 ExperienceOrb → tickXpPickup 吸收 → 等级按 vanilla 公式提升 → BotState 暴露',
        'state_change': 'experienceLevel/experienceProgress/totalExperience 更新，经验球被吸收',
        'observable_result': '击杀僵尸后经验球出现、拾取后等级提升、harness 可读取 xp 字段',
        'failure_behavior': 'setLastHurtByPlayer 未调用时击杀不掉经验球，facade XP 不同步时菜单无法消耗',
    },
    '009': {
        'actor': 'MenuOpener + BindingMenu + BotPlayerFacade (adapter layer)',
        'precondition': 'harness 请求打开铁砧/酿造台/附魔台/村民交易',
        'trigger': 'MenuOpener.open(blockPos) 或 openEntity(entityId)',
        'rule': 'generic MenuProvider 回退路径打开方块菜单 → openEntity 打开村民 → facade.syncExperience 同步 XP → setAnvilName/clickButton 执行操作',
        'state_change': '菜单打开，XP 字段同步，操作执行后 XP 消耗',
        'observable_result': '铁砧显示正确 XP 等级、重命名生效、附魔选项可选、村民交易可打开',
        'failure_behavior': 'facade XP 永远为 0 时菜单显示无 XP，MenuProvider 回退缺失时菜单打不开',
    },
    '010': {
        'actor': 'DigExecutor (adapter layer)',
        'precondition': '方块破坏完成，手持物品非空',
        'trigger': 'DigExecutor 手动 break 序列完成后',
        'rule': 'held.getItem().mineBlock(held, level, state, pos, body): DiggerItem 伤1、SwordItem 伤2、ShearsItem 伤1、基类不伤; gate 在 destroySpeed!=0',
        'state_change': '工具耐久减少，可能触发 Unbreaking 豁免或 Mending 修复',
        'observable_result': '镐挖 100 块后耐久条减少，瞬破方块（火把）不消耗',
        'failure_behavior': 'mineBlock 未调用时工具无限耐久，与 vanilla 不一致',
    },
    '011': {
        'actor': 'DigExecutor (adapter layer)',
        'precondition': '方块破坏完成',
        'trigger': 'DigExecutor 手动 break 序列完成后',
        'rule': 'body.getFoodData().addExhaustion(0.005F)，mirror vanilla Block.playerDestroy',
        'state_change': 'foodData.exhaustionLevel 累积，>4.0 时消耗 saturation，再耗尽消耗 foodLevel',
        'observable_result': '连续挖掘后饥饿值下降，HungerTicker 正常处理 exhaustion',
        'failure_behavior': 'exhaustion 未应用时挖掘不消耗饥饿，与 vanilla 不一致',
    },
    '012': {
        'actor': 'MeleeResolver (adapter layer)',
        'precondition': '主攻击命中，手持剑',
        'trigger': 'performMeleeAttack 主 hurt 成功后',
        'rule': '!crit && !isSprinting && onGround && SWORD_SWEEP → 伤害=1.0+SweepingEdge比率×主伤害, 击退0.4, getSweepHitBox 范围内排除自己/主目标/同盟',
        'state_change': '附近敌人受到横扫伤害和击退，播放 PLAYER_ATTACK_SWEEP',
        'observable_result': '多怪站位时附近敌人受到横扫伤害，疾跑时不横扫（改为击退加成）',
        'failure_behavior': '条件错误时疾跑也可能横扫，或非剑武器也触发横扫',
    },
    '013': {
        'actor': 'Gradle build system (tool layer)',
        'precondition': '代码改动完成',
        'trigger': 'python tool/mcbot_tool.py test',
        'rule': 'compileJava → checkstyleMain → spotlessCheck → test (539 JUnit)，全部通过才 BUILD SUCCESSFUL',
        'state_change': 'build/ 目录生成编译产物和测试报告',
        'observable_result': 'BUILD SUCCESSFUL，0 failures，0 errors',
        'failure_behavior': '任一阶段失败时 BUILD FAILED，需修复后重跑',
    },
}

impact_scopes = {
    '001': ['core/pathing/BasicMoves.java', 'core/pathing/PathingBehavior.java', 'api/pathing/BotMove.java'],
    '002': ['core/actor/DigPacing.java', 'adapter/DigExecutor.java', 'api/actor/DigIntent.java'],
    '003': ['core/pathing/BasicMoves.java', 'core/pathing/PathingBehavior.java', 'adapter/entity/BotBodyEntity.java'],
    '004': ['adapter/DigExecutor.java', 'adapter/entity/GroundPickup.java', 'core/actor/DigPacing.java'],
    '005': ['adapter/MeleeResolver.java', 'api/actor/Intent.java'],
    '006': ['adapter/MeleeResolver.java'],
    '007': ['adapter/entity/BotBodyEntity.java'],
    '008': ['adapter/entity/BotBodyEntity.java', 'adapter/BotPlayerFacade.java', 'adapter/MeleeResolver.java', 'api/state/BotState.java', 'api/state/BotStateJson.java'],
    '009': ['adapter/MenuOpener.java', 'adapter/MenuSlotLayouts.java', 'adapter/BindingMenu.java', 'adapter/BotPlayerFacade.java'],
    '010': ['adapter/DigExecutor.java'],
    '011': ['adapter/DigExecutor.java', 'adapter/entity/HungerTicker.java'],
    '012': ['adapter/MeleeResolver.java'],
    '013': ['全量 src/main/java', '全量 src/test/java', 'build.gradle', 'gradle.properties'],
}

for req in data['requirements']:
    seq = req['id'].split('-')[-1]
    if seq in behavior_dicts:
        req['behavior'] = behavior_dicts[seq]
    if seq in impact_scopes:
        req['impact_scope'] = impact_scopes[seq]

# Fix risk_mechanisms: severity -> priority, add status
for rm in data['risk_mechanisms']:
    if 'severity' in rm and 'priority' not in rm:
        rm['priority'] = rm.pop('severity')
    if 'status' not in rm:
        rm['status'] = 'identified'

with open('qa-results/mc-bot-mechanics/qa-run.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

beh_ok = sum(1 for r in data['requirements'] if isinstance(r.get('behavior'), dict) and all(r['behavior'].get(k) for k in ['actor','precondition','trigger','rule','state_change','observable_result','failure_behavior']))
imp_ok = sum(1 for r in data['requirements'] if isinstance(r.get('impact_scope'), list) and r['impact_scope'])
rm_ok = sum(1 for r in data['risk_mechanisms'] if r.get('priority') and r.get('status'))
print(f'Requirements: behavior dict {beh_ok}/13, impact_scope list {imp_ok}/13')
print(f'Risk mechanisms: priority+status {rm_ok}/{len(data["risk_mechanisms"])}')
