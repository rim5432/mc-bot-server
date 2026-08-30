import json

with open('qa-results/ranged-survival/qa-run.json', 'r', encoding='utf-8') as f:
    data = json.load(f)

# === SOURCES ===
data['input']['summary'] = '远程/生存能力面 6 个机制面的 QA 梳理：弓、钓鱼、盾、睡眠、工具选择、救援。普查确认 67 个已钉 gametest 中零正向覆盖，离线 gate 只钉 shape。'
data['input']['sources'] = [
    {'id': 'SRC-CODEBASE', 'type': 'directory', 'locator': 'D:/mc-bot-server', 'version': 'dev 0.1.0', 'access_status': 'read', 'completeness_checked': True, 'coverage_note': '182 Java sources; 6 mechanism surfaces targeted'},
    {'id': 'SRC-VANILLA', 'type': 'directory', 'locator': 'D:/mc-decompiled/forge-1.20.1-47.4.10', 'version': '1.20.1 Forge 47.4.10', 'access_status': 'read', 'completeness_checked': True, 'coverage_note': 'vanilla reference for bow draw, shield block, fish bobber, sleep'},
]

# === ARTIFACTS ===
artifacts = [
    ('SRC-001', 'BindingActor.java', 'src/main/java/com/mcbot/mcbotserver/adapter/BindingActor.java', 'REQ-001, REQ-005, REQ-006'),
    ('SRC-002', 'FishBehavior.java', 'src/main/java/com/mcbot/mcbotserver/core/behavior/FishBehavior.java', 'REQ-003, REQ-004'),
    ('SRC-003', 'RangedLoadouts.java', 'src/main/java/com/mcbot/mcbotserver/core/combat/RangedLoadouts.java', 'REQ-002'),
    ('SRC-004', 'BobberSnapshot.java', 'src/main/java/com/mcbot/mcbotserver/api/world/BobberSnapshot.java', 'REQ-004'),
    ('SRC-005', 'CombatBehavior.java', 'src/main/java/com/mcbot/mcbotserver/core/behavior/CombatBehavior.java', 'REQ-002'),
    ('SRC-006', 'DefendProcess.java', 'src/main/java/com/mcbot/mcbotserver/core/process/DefendProcess.java', 'REQ-002'),
    ('SRC-007', 'BotBodyEntity.java', 'src/main/java/com/mcbot/mcbotserver/adapter/entity/BotBodyEntity.java', 'REQ-005, REQ-006, REQ-007'),
    ('SRC-008', 'ToolSelector.java', 'src/main/java/com/mcbot/mcbotserver/core/tick/ToolSelector.java', 'REQ-008'),
    ('SRC-009', 'RescueMissionFactory.java', 'src/main/java/com/mcbot/mcbotserver/adapter/rescue/RescueMissionFactory.java', 'REQ-009'),
    ('SRC-010', 'GametestInventoryCheck.java', 'src/test/java/com/mcbot/mcbotserver/hygiene/GametestInventoryCheck.java', 'REQ-010'),
    ('SRC-011', 'FishBehaviorGateTest.java', 'src/test/java/com/mcbot/mcbotserver/core/behavior/FishBehaviorGateTest.java', 'REQ-003'),
    ('SRC-012', 'CombatRangedGateTest.java', 'src/test/java/com/mcbot/mcbotserver/core/behavior/CombatRangedGateTest.java', 'REQ-002'),
    ('SRC-013', 'ToolSelectorTest.java', 'src/test/java/com/mcbot/mcbotserver/core/tick/ToolSelectorTest.java', 'REQ-008'),
]
data['input']['artifacts'] = [
    {'id': aid, 'source_id': 'SRC-CODEBASE', 'name': name, 'path': path, 'type': 'source', 'locator': 'D:/mc-bot-server/' + path, 'access_status': 'read', 'completeness_checked': True, 'integrity': 'complete', 'covers': covers}
    for aid, name, path, covers in artifacts
]

# === REQUIREMENTS (with structured behavior dicts) ===
reqs = [
    {
        'id': 'REQ-001', 'risk': 'P0', 'title': '弓蓄力与释放',
        'statement': 'BindingActor 识别 BowItem 后调 facade.startUsingItem() 开始蓄力，USE falling edge 调 facade.releaseUsingItem() 射出；蓄力时长由 facade.tickUseLoop 每 held tick 泵动',
        'behavior': {
            'actor': 'BindingActor (adapter layer)',
            'precondition': 'bot 手持弓，USE intent rising edge 到达',
            'trigger': 'onUsePressEdge() 检测 held instanceof BowItem',
            'rule': 'BowItem → facade.startUsingItem(MAIN_HAND)；每 held tick facade.tickUseLoop() 泵动蓄力；falling edge → facade.releaseUsingItem()；vanilla BowItem 释放时根据 useDuration 计算箭速和伤害',
            'state_change': 'facade 进入 use 状态，useTicks 累积；释放后生成 Arrow 实体',
            'observable_result': '蓄力 20 tick 后释放生成满速箭；蓄力不足生成慢速弱伤箭',
            'failure_behavior': 'startUsingItem 未调用时弓不蓄力，释放无箭；facade 与 body 姿态不同步时箭从错误位置射出',
        },
        'impact_scope': ['adapter/BindingActor.java', 'adapter/BotPlayerFacade.java', 'api/actor/Intent.java'],
    },
    {
        'id': 'REQ-002', 'risk': 'P1', 'title': '远程负载检测与 SLOT 通道',
        'statement': 'RangedLoadouts.hotbarBowSlot() 检测 hotbar 有弓且 inventory 任意位置有箭；CombatBehavior 远程时 SLOT 通道归弓所有；DefendProcess 无远程负载时 ENGAGEMENT_REFUSED',
        'behavior': {
            'actor': 'RangedLoadouts + CombatBehavior + DefendProcess (core layer)',
            'precondition': 'bot 进入战斗或防御模式',
            'trigger': 'DefendProcess 评估敌对目标时调用 RangedLoadouts.hotbarBowSlot()',
            'rule': 'hotbar 扫描 BowItem → inventory 全扫描 arrow/tipped_arrow/spectral_arrow → 两者都有时返回弓的 slot；CombatBehavior 远程模式下 Claim(Channel.SLOT) 归弓；无弓或无箭时 DefendProcess 返回 ENGAGEMENT_REFUSED',
            'state_change': 'SLOT 通道被弓 claim，selectedSlot 切换到弓',
            'observable_result': '有弓有箭时 bot 切换到弓并远程攻击；无弓或无箭时拒绝远程交战',
            'failure_behavior': 'arrow 检测遗漏 tipped_arrow/spectral_arrow 时误判无远程负载；SLOT 通道未归弓时弓和近战武器冲突',
        },
        'impact_scope': ['core/combat/RangedLoadouts.java', 'core/behavior/CombatBehavior.java', 'core/process/DefendProcess.java'],
    },
    {
        'id': 'REQ-003', 'risk': 'P0', 'title': '钓鱼状态机',
        'statement': 'FishBehavior 完整状态机：无浮漂时 cast(USE rising edge) → 浮漂出现后 settle(30t grace + 5t stable) → armed 后 bite watch(DIP_THRESHOLD=0.25, BITE_BUDGET=600t) → 咬钩或预算耗尽时 reel',
        'behavior': {
            'actor': 'FishBehavior (core behavior layer)',
            'precondition': 'directive.overrides().fish() 非空，hotbar 有钓鱼竿',
            'trigger': 'Behavior.tick() 每 server tick 调用',
            'rule': '无 bobber → tickCast (USE true→false 抛竿)；有 bobber 未 armed → tickSettle (castAge>30 后 5 tick 稳定即 armed)；armed → tickBiteWatch (lastY - currentY >= 0.25 即咬钩 reel；noBiteTicks>600 预算耗尽 reel)；hookedEntity 时直接 reel 重置',
            'state_change': 'pressPending/castAge/settleTicks/lastBobberY/noBiteTicks/armed/budgetSpent 状态流转',
            'observable_result': '抛竿后浮漂飞入水中；稳定后咬钩（垂直下落>=0.25）时收竿；600 tick 无咬钩时超时收竿',
            'failure_behavior': 'CAST_GRACE_TICKS 过短导致浮漂还在飞就 armed；DIP_THRESHOLD 过高漏检咬钩、过低误检水波；BITE_BUDGET 过短正常咬钩前就超时',
        },
        'impact_scope': ['core/behavior/FishBehavior.java', 'api/world/BobberSnapshot.java', 'api/process/Fish.java'],
    },
    {
        'id': 'REQ-004', 'risk': 'P0', 'title': '咬钩检测与浮漂感知',
        'statement': 'BobberSnapshot 只携带 pos + hookedEntity，无 bite flag（vanilla nibble 私有）；咬钩从连续快照的垂直 dip 推导；WorldView.getBobbers(waterCell, radius, LIVE) 返回浮漂列表',
        'behavior': {
            'actor': 'BobberSnapshot + WorldView (api layer)',
            'precondition': '钓鱼竿已抛出，水中存在 FishingHook 实体',
            'trigger': 'FishBehavior.tick() 调用 world.getBobbers(fish.waterCell(), 24.0, ViewMode.LIVE)',
            'rule': 'BobberSnapshot(CellPos pos, boolean hookedEntity) — 无 bite 字段；咬钩 = 连续两 tick 浮漂 y 坐标下降 >= 0.25（DIP_THRESHOLD）；hookedEntity=true 表示钩到实体/物品（非鱼咬钩），直接收竿重置',
            'state_change': 'FishBehavior.lastBobberY 更新；检测到 dip 时触发 reel',
            'observable_result': '鱼咬钩时浮漂垂直急坠，bot 在下一 tick 收竿；钩到物品/实体时 hookedEntity=true 立即收竿',
            'failure_behavior': 'BobberSnapshot 误加 bite flag 时违反 boundary A（projectile 不可见，nibble 私有）；getBobbers 半径不足时找不到浮漂；waterCell 定位错误时扫描范围偏移',
        },
        'impact_scope': ['api/world/BobberSnapshot.java', 'api/world/WorldView.java', 'adapter/BindingWorldView.java'],
    },
    {
        'id': 'REQ-005', 'risk': 'P0', 'title': '盾举盾与收盾',
        'statement': 'BindingActor 识别 ShieldItem 后调 body.startUsingItem(MAIN_HAND)（在 body 上 raise 而非 facade）；USE falling edge 调 body.releaseUsingItem()；body.isUsingItem() 检测举盾状态',
        'behavior': {
            'actor': 'BindingActor (adapter layer)',
            'precondition': 'bot 手持盾，USE intent rising edge 到达',
            'trigger': 'onUsePressEdge() 检测 held instanceof ShieldItem',
            'rule': 'ShieldItem → body.startUsingItem(MAIN_HAND)（非 facade，因为 isDamageSourceBlocked 读受伤实体自己的 blocking 状态）；每 held tick 保持 use；falling edge → body.releaseUsingItem()；releaseUseHolds() 同时检查 facade.isUsingItem() 和 body.isUsingItem()',
            'state_change': 'body 进入 use 状态，activeItem 为盾；useDuration 累积；释放后退出 use 状态',
            'observable_result': '举盾时 bot 模型显示盾格挡姿势；收盾后恢复正常；受到攻击时 isDamageSourceBlocked 检查 body 的 use 状态',
            'failure_behavior': '在 facade 上 startUsingItem 时 isDamageSourceBlocked 读不到（facade 不受伤），盾完全无效；releaseUseHolds 遗漏 body.isUsingItem() 检查时盾无法收盾',
        },
        'impact_scope': ['adapter/BindingActor.java', 'adapter/entity/BotBodyEntity.java'],
    },
    {
        'id': 'REQ-006', 'risk': 'P0', 'title': '盾伤害减免判定',
        'statement': 'vanilla LivingEntity.isDamageSourceBlocked() 检查受伤实体自己的 isUsingItem() && activeItem 是盾 && useDuration >= 5；满足时伤害减免（部分或全部，取决于角度和盾牌附魔）；bot 受击时走 body 的 hurt 路径',
        'behavior': {
            'actor': 'BotBodyEntity (adapter entity layer) + vanilla LivingEntity',
            'precondition': 'bot 举盾中（body.isUsingItem() && activeItem instanceof ShieldItem），受到伤害源攻击',
            'trigger': 'body.hurt(damageSource, amount) 被调用',
            'rule': 'vanilla LivingEntity.hurt() → isDamageSourceBlocked(source) 检查：isUsingItem() && getUseItem().isShield(source) && getUseItemRemainingTicks() <= 0（useDuration>=5）&& 伤害源不在背后 180° 扇形外 → 减免伤害（全挡或部分挡，盾牌附魔 Resistance 影响）；触发 shieldDisabled 效果（斧攻击破盾）',
            'state_change': '伤害被减免或全挡；盾可能被斧击破（100 tick 无法使用）；伤害源 knockback 被取消',
            'observable_result': '举盾正面受击时伤害减免（盾挡音效）；背后受击时不减免；斧攻击时盾被击破（100t 冷却）',
            'failure_behavior': '盾在 facade 上 raise 时 body.isUsingItem() 为 false，isDamageSourceBlocked 永远 false，盾完全无效；useDuration 不足 5 tick 时（刚举盾就被打）不减免',
        },
        'impact_scope': ['adapter/entity/BotBodyEntity.java', 'adapter/BindingActor.java'],
    },
    {
        'id': 'REQ-007', 'risk': 'P2', 'title': '睡眠机制',
        'statement': 'BotBodyEntity extends PathfinderMob（PathfinderMob 有 sleep 能力，原用于狐狸）；需确认 bot 是否有主动睡眠/起床机制，或仅继承 base class 能力',
        'behavior': {
            'actor': 'BotBodyEntity (adapter entity layer)',
            'precondition': '待确认：是否存在主动睡眠触发条件（夜晚、床、疲劳等）',
            'trigger': '待确认：是否有 behavior/process 驱动睡眠',
            'rule': 'PathfinderMob base class 有 isSleeping()/setSleeping()/startSleeping()/stopSleeping()；Sleeping 状态影响 AI（不移动、不被攻击唤醒？）；bot 是否 override 这些方法待确认',
            'state_change': '待确认：进入/退出 sleeping 状态时的行为变化',
            'observable_result': '待确认：是否能在游戏中观察到 bot 睡眠',
            'failure_behavior': '若 bot 无主动睡眠机制但 PathfinderMob base class 的 sleep AI 被意外触发，bot 可能在不适当的时候进入睡眠；若 override 缺失，base class sleep 行为可能与 bot 设计冲突',
        },
        'impact_scope': ['adapter/entity/BotBodyEntity.java'],
    },
    {
        'id': 'REQ-008', 'risk': 'P1', 'title': '工具自动选择',
        'statement': 'ToolSelector 根据目标方块选择最优工具（破坏速度最快 + match_tool）；挖掘时自动切换 hotbar 槽位；离线 ToolSelectorTest 只验证选择逻辑，不验证实际切换',
        'behavior': {
            'actor': 'ToolSelector (core tick layer)',
            'precondition': 'bot 开始挖掘目标方块，hotbar 有多个工具',
            'trigger': 'DigExecutor 或 tick 层调用 ToolSelector 选择工具',
            'rule': '遍历 hotbar 工具，计算每个工具对目标方块的 destroySpeed（含附魔效率），选择最快且满足 match_tool 的工具；通过 SLOT 通道 Claim 切换 selectedSlot',
            'state_change': 'selectedSlot 切换到最优工具，DigExecutor 使用新工具继续挖掘',
            'observable_result': '挖石头时自动切换到镐（而非空手或剑）；挖树叶时切换到剪刀；工具耐久耗尽时切换到次优工具',
            'failure_behavior': 'ToolSelector 只看 destroySpeed 不看 match_tool 时可能选剑挖石头（速度快但不掉落）；SLOT 通道未正确切换时 DigExecutor 仍用旧工具；离线测试通过但引擎内切换失败',
        },
        'impact_scope': ['core/tick/ToolSelector.java', 'adapter/DigExecutor.java', 'api/actor/Intent.java'],
    },
    {
        'id': 'REQ-009', 'risk': 'P2', 'title': '救援任务',
        'statement': 'RescueMissionFactory 生成救援任务（bot 陷入危险时的自动逃生路径）；需确认机制面和触发条件',
        'behavior': {
            'actor': 'RescueMissionFactory (adapter rescue layer)',
            'precondition': '待确认：触发条件（低血量、熔岩、溺水、卡住等）',
            'trigger': '待确认：哪个 reflex/process 调用 RescueMissionFactory',
            'rule': '待确认：救援任务的生成逻辑、路径规划、执行流程',
            'state_change': '待确认：救援任务执行时的状态流转',
            'observable_result': '待确认：救援任务的可观察行为',
            'failure_behavior': '待确认：救援任务失败模式（路径不可达、任务中断等）',
        },
        'impact_scope': ['adapter/rescue/RescueMissionFactory.java'],
    },
    {
        'id': 'REQ-010', 'risk': 'P0', 'title': '无回归',
        'statement': '新增 gametest 场景不破坏已有 67 个场景；GametestInventoryCheck 钉住场景清单；全量离线测试通过',
        'behavior': {
            'actor': 'Gradle build + GametestInventoryCheck (hygiene layer)',
            'precondition': '新增 gametest 场景已写入 src/main/java/com/mcbot/mcbotserver/gametest/',
            'trigger': 'python tool/mcbot_tool.py test',
            'rule': 'compileJava → checkstyle → spotless → test (539+ offline)；GametestInventoryCheck 扫描 gametest 源码 @GameTest 方法并与 EXPECTED 钉表双向比对',
            'state_change': 'EXPECTED 钉表更新，场景清单扩展',
            'observable_result': 'BUILD SUCCESSFUL，GametestInventoryCheck 通过，场景数 67→N',
            'failure_behavior': '新增场景未更新 EXPECTED 时 GametestInventoryCheck fail；场景被意外删除时同样 fail（routesThroughFenceGap 事故模式）',
        },
        'impact_scope': ['src/main/java/com/mcbot/mcbotserver/gametest/', 'src/test/java/com/mcbot/mcbotserver/hygiene/GametestInventoryCheck.java'],
    },
]
data['requirements'] = reqs

# === RISK MECHANISMS ===
rms = [
    {
        'id': 'RM-001', 'priority': 'P0', 'status': 'identified',
        'title': '盾在 facade 上 raise 导致完全无效',
        'mechanism': 'isDamageSourceBlocked 读受伤实体（body）自己的 isUsingItem()，若盾在 facade 上 startUsingItem 则 body.isUsingItem() 为 false，盾永远不格挡',
        'failure_mode': 'BindingActor.onUsePressEdge() 对 ShieldItem 调用 facade.startUsingItem() 而非 body.startUsingItem()',
        'business_impact': '盾完全无效，bot 受到全额伤害，生存能力严重下降；这是 adapter 层 P0 产地的典型 bug',
        'oracle': 'bot 举盾正面受击时伤害应减免（盾挡音效）；若伤害全额则盾无效',
        'requirement_ids': ['REQ-005', 'REQ-006'],
    },
    {
        'id': 'RM-002', 'priority': 'P0', 'status': 'identified',
        'title': '弓蓄力时长与箭速/伤害不匹配 vanilla',
        'mechanism': 'vanilla BowItem 释放时根据 useDuration 计算箭速（满蓄力 20 tick = 3.0 速度）和伤害（满蓄力 = 9+ 心）；facade.tickUseLoop 泵动的 useTicks 若与 vanilla 不同步，箭速/伤害偏离',
        'failure_mode': 'facade.tickUseLoop() 未每 held tick 调用，或 useTicks 累积速率与 vanilla Player.tick() 不同步',
        'business_impact': '弓伤害/箭速与 vanilla 不一致，远程战斗体验失真；可能过强或过弱',
        'oracle': '蓄力 20 tick 后释放的箭应飞行满速（约 3.0 blocks/tick），伤害约 9 心；蓄力 10 tick 应约半伤半速',
        'requirement_ids': ['REQ-001'],
    },
    {
        'id': 'RM-003', 'priority': 'P0', 'status': 'identified',
        'title': '钓鱼咬钩检测 DIP_THRESHOLD 误判',
        'mechanism': 'FishBehavior 用连续两 tick 浮漂 y 下降 >= 0.25 判定咬钩；vanilla 咬钩 kick 约 0.4 blocks/tick，水波约 0.05；阈值过高漏检、过低误检水波',
        'failure_mode': 'DIP_THRESHOLD=0.25 设置不当，或 lastBobberY 更新逻辑错误（如咬钩 tick 未更新 lastY 导致连续触发）',
        'business_impact': '咬钩漏检时鱼跑了不收竿；误检时频繁空收竿，钓鱼效率归零',
        'oracle': '抛竿后鱼咬钩（浮漂垂直急坠）时 bot 应在 1-2 tick 内收竿；正常水波浮动不应触发收竿',
        'requirement_ids': ['REQ-003', 'REQ-004'],
    },
    {
        'id': 'RM-004', 'priority': 'P1', 'status': 'identified',
        'title': '钓鱼 CAST_GRACE_TICKS 过短导致浮漂未落地就 armed',
        'mechanism': 'FishBehavior 抛竿后 30 tick grace 期忽略浮漂运动，之后 5 tick 稳定即 armed；若 grace 过短，浮漂还在飞行中就被判定为"不稳定"，settleTicks 永远达不到 5',
        'failure_mode': 'CAST_GRACE_TICKS=30 设置过短，或浮漂飞行时间因抛竿角度/力度变化而超过 30 tick',
        'business_impact': '钓鱼行为卡在 settle 阶段永远不 armed，咬钩检测永不启动，钓鱼完全失效',
        'oracle': '抛竿后浮漂飞入水中（约 10-20 tick），之后 5 tick 稳定即 armed；armed 后咬钩检测生效',
        'requirement_ids': ['REQ-003'],
    },
    {
        'id': 'RM-005', 'priority': 'P1', 'status': 'identified',
        'title': '远程负载检测遗漏 tipped_arrow/spectral_arrow',
        'mechanism': 'RangedLoadouts.ARROW_IDS 包含 arrow/tipped_arrow/spectral_arrow；若遗漏任一类型，bot 持有该箭时误判无远程负载，拒绝远程交战',
        'failure_mode': 'ARROW_IDS Set 不完整，或 inventory 扫描逻辑遗漏 offhand/armor 槽位',
        'business_impact': 'bot 持有药箭或光灵箭时无法远程攻击，战斗策略受限',
        'oracle': 'hotbar 有弓 + inventory 有 tipped_arrow 时 RangedLoadouts.hotbarBowSlot() 返回弓的 slot；有 spectral_arrow 时同样返回',
        'requirement_ids': ['REQ-002'],
    },
    {
        'id': 'RM-006', 'priority': 'P1', 'status': 'identified',
        'title': '工具选择只看 destroySpeed 不看 match_tool',
        'mechanism': 'ToolSelector 若只比较 destroySpeed 而不检查 match_tool，可能选剑挖石头（剑 destroySpeed 对部分方块快于镐？）或选错误工具，导致不掉落物品',
        'failure_mode': 'ToolSelector 选择逻辑未纳入 match_tool 条件，或 DigExecutor 未使用 ToolSelector 的结果',
        'business_impact': 'bot 挖石头不掉圆石、挖矿石不掉矿石，资源获取失效',
        'oracle': '挖石头时 ToolSelector 选镐（非剑/空手），DigExecutor 使用镐挖掘，掉落圆石',
        'requirement_ids': ['REQ-008'],
    },
    {
        'id': 'RM-007', 'priority': 'P2', 'status': 'identified',
        'title': '盾 useDuration 不足 5 tick 时不格挡',
        'mechanism': 'vanilla isDamageSourceBlocked 要求 getUseItemRemainingTicks() <= 0（即 useDuration >= 5）；刚举盾 0-4 tick 内受击不格挡',
        'failure_mode': 'bot 反应延迟导致举盾后 5 tick 内被击中，盾未生效',
        'business_impact': 'bot 举盾反应慢时第一击全额伤害，生存能力下降',
        'oracle': '举盾 5 tick 后正面受击伤害减免；举盾 0-4 tick 内受击不减免',
        'requirement_ids': ['REQ-006'],
    },
    {
        'id': 'RM-008', 'priority': 'P2', 'status': 'identified',
        'title': '睡眠机制未确认是否存在主动触发',
        'mechanism': 'BotBodyEntity extends PathfinderMob，base class 有 sleep 能力但原用于狐狸；bot 是否有主动睡眠/起床机制、是否 override sleep 方法待确认',
        'failure_mode': '若 base class sleep AI 被意外触发，bot 可能在不适当的时候进入睡眠；若 override 缺失，base class 行为可能与 bot 设计冲突',
        'business_impact': 'bot 意外睡眠时停止响应，任务中断；或睡眠机制完全缺失，夜间生存策略受限',
        'oracle': '待确认：bot 是否能在游戏中观察到睡眠/起床行为',
        'requirement_ids': ['REQ-007'],
    },
    {
        'id': 'RM-009', 'priority': 'P2', 'status': 'identified',
        'title': '救援任务机制面未确认',
        'mechanism': 'RescueMissionFactory 存在但触发条件、生成逻辑、执行流程均未在普查中确认；可能是 stub 或半成品',
        'failure_mode': 'RescueMissionFactory 可能是空实现或未被任何 reflex/process 调用',
        'business_impact': 'bot 陷入危险（熔岩、溺水、卡住）时无自动逃生路径',
        'oracle': '待确认：RescueMissionFactory 的实际机制面和可观察行为',
        'requirement_ids': ['REQ-009'],
    },
    {
        'id': 'RM-010', 'priority': 'P0', 'status': 'identified',
        'title': '新增 gametest 场景未更新钉表导致 GametestInventoryCheck fail',
        'mechanism': 'GametestInventoryCheck.EXPECTED 钉住全部场景名；新增场景未更新 EXPECTED 时测试 fail（双向比对）',
        'failure_mode': '新增 @GameTest 方法后忘记更新 EXPECTED Map，或场景名拼写不一致',
        'business_impact': '全量测试 fail，无法提交；routesThroughFenceGap 事故模式（场景被意外删除但离线测试全绿）',
        'oracle': '新增场景后 GametestInventoryCheck 通过，EXPECTED 包含新场景名，场景数 67→N',
        'requirement_ids': ['REQ-010'],
    },
]
data['risk_mechanisms'] = rms

# === TEST CASES ===
cases = []
tc = 1
def add(req_id, title, pri, desc, module, ctype, steps, expected, rm_ids, test_data, blocking=None):
    global tc
    c = {
        'id': f'TC-{tc:03d}', 'requirement_id': req_id, 'requirement_ids': [req_id],
        'title': title, 'priority': pri, 'status': 'not_executed', 'validation_scope': 'formal',
        'description': desc, 'module': module, 'type': ctype,
        'steps': steps, 'expected_result': expected,
        'risk_mechanism_ids': rm_ids, 'test_data': test_data,
    }
    if blocking:
        c['release_blocking_reason'] = blocking
    cases.append(c)
    tc += 1

# Bow cases
add('REQ-001', '弓蓄力20tick满速射出', 'P0',
    'bot 手持弓蓄力 20 tick 后释放，验证箭速和伤害为满值',
    'combat', 'gametest',
    ['前置: bot 手持弓+箭，站在平地', '执行: 提交 USE hold 20 tick 后释放', '观察: 记录 Arrow 实体初速度和伤害'],
    '箭初速度约 3.0 blocks/tick，伤害约 9 心（满蓄力）',
    ['RM-002'], 'fixture: bow+64 arrows, flat platform, target zombie at 10 blocks',
    '弓蓄力/箭速是远程战斗核心机制，失效则远程完全不可用')
add('REQ-001', '弓蓄力不足半速半伤', 'P1',
    'bot 手持弓蓄力 10 tick 后释放，验证箭速和伤害约为半值',
    'combat', 'gametest',
    ['前置: bot 手持弓+箭', '执行: USE hold 10 tick 后释放', '观察: 记录箭速和伤害'],
    '箭速约 1.5 blocks/tick，伤害约 4-5 心（半蓄力）',
    ['RM-002'], 'fixture: bow+arrows, flat platform')
add('REQ-001', '弓释放后facade退出use状态', 'P1',
    '弓释放后 facade.isUsingItem() 应为 false，useTicks 重置',
    'combat', 'gametest',
    ['前置: bot 手持弓蓄力中', '执行: USE falling edge 释放', '观察: facade.isUsingItem() 状态'],
    '释放后 facade.isUsingItem()=false，activeItem 为空',
    ['RM-002'], 'fixture: bow+arrows')

# Ranged loadout cases
add('REQ-002', '有弓有箭时切换到弓', 'P1',
    'hotbar 有弓且 inventory 有箭时，RangedLoadouts.hotbarBowSlot() 返回弓的 slot',
    'combat', 'unit',
    ['前置: hotbar slot 2 有弓，inventory 有 arrow', '执行: 调用 RangedLoadouts.hotbarBowSlot(world)', '观察: 返回值'],
    '返回 2（弓的 slot）',
    ['RM-005'], 'fixture: bow at hotbar[2], 16 arrows in inventory')
add('REQ-002', '有弓无箭时返回-1', 'P1',
    'hotbar 有弓但 inventory 无箭时，RangedLoadouts 返回 -1',
    'combat', 'unit',
    ['前置: hotbar 有弓，inventory 无箭', '执行: hotbarBowSlot()', '观察: 返回值'],
    '返回 -1',
    ['RM-005'], 'fixture: bow at hotbar[0], no arrows')
add('REQ-002', 'tipped_arrow和spectral_arrow被识别', 'P1',
    'inventory 有 tipped_arrow 或 spectral_arrow 时，RangedLoadouts 识别为有箭',
    'combat', 'unit',
    ['前置: hotbar 有弓，inventory 有 tipped_arrow', '执行: hotbarBowSlot()', '观察: 返回值；再用 spectral_arrow 重复'],
    '两种箭都返回弓的 slot（非 -1）',
    ['RM-005'], 'fixture: bow + tipped_arrow; bow + spectral_arrow')

# Fishing cases
add('REQ-003', '抛竿后浮漂生成并入水', 'P0',
    'bot 手持钓鱼竿对水面 USE，验证 FishingHook 实体生成并飞入水中',
    'fishing', 'gametest',
    ['前置: bot 手持钓鱼竿，面前 3x3 水池', '执行: USE rising edge 抛竿', '观察: 水池中是否出现 FishingHook 实体'],
    '抛竿后 1-2 tick 内水池中出现浮漂实体',
    ['RM-003', 'RM-004'], 'fixture: fishing_rod, 3x3 water pool at eye level',
    '抛竿是钓鱼入口，失效则钓鱼完全不可用')
add('REQ-003', '浮漂稳定后armed状态', 'P1',
    '抛竿后浮漂入水稳定（5 tick），FishBehavior 进入 armed 状态',
    'fishing', 'gametest',
    ['前置: 已抛竿，浮漂在水中', '执行: 等待 30 tick grace + 5 tick 稳定', '观察: FishBehavior.armed 状态（通过行为推断：咬钩检测是否生效）'],
    '约 35 tick 后 armed，咬钩检测生效',
    ['RM-004'], 'fixture: fishing_rod, stable water pool')
add('REQ-004', '咬钩垂直dip触发收竿', 'P0',
    '浮漂 armed 后，模拟鱼咬钩（浮漂垂直急坠 >=0.25），验证 bot 收竿',
    'fishing', 'gametest',
    ['前置: 已抛竿且 armed', '执行: 等待自然咬钩或模拟浮漂垂直下落', '观察: bot 是否在 1-2 tick 内 USE edge 收竿'],
    '浮漂垂直下落 >=0.25 时 bot 收竿',
    ['RM-003'], 'fixture: fishing_rod, water pool with fish (或手动移动浮漂)',
    '咬钩检测是钓鱼核心，失效则鱼跑了不收竿')
add('REQ-004', '水波浮动不触发收竿', 'P1',
    '浮漂正常水波浮动（<0.25）不应触发收竿',
    'fishing', 'gametest',
    ['前置: 已抛竿且 armed，无鱼', '执行: 观察 100 tick 自然浮动', '观察: bot 是否误收竿'],
    '100 tick 内无咬钩时不收竿（或到 BITE_BUDGET=600 才超时收竿）',
    ['RM-003'], 'fixture: fishing_rod, calm water, no fish')
add('REQ-003', 'BITE_BUDGET超时收竿', 'P2',
    '600 tick 无咬钩时 FishBehavior 超时收竿并重置',
    'fishing', 'gametest',
    ['前置: 已抛竿且 armed，无鱼', '执行: 等待 600+ tick', '观察: bot 是否收竿'],
    '600 tick 后 bot 收竿，状态重置',
    ['RM-004'], 'fixture: fishing_rod, water pool no fish')
add('REQ-004', 'hookedEntity时立即收竿重置', 'P2',
    '浮漂钩到实体/物品（hookedEntity=true）时立即收竿，不等待咬钩',
    'fishing', 'gametest',
    ['前置: 已抛竿', '执行: 让浮漂钩到物品实体', '观察: bot 是否立即收竿'],
    'hookedEntity=true 时立即收竿并重置 watch',
    ['RM-003'], 'fixture: fishing_rod, item entity in water')

# Shield cases
add('REQ-005', '盾在body上raise而非facade', 'P0',
    'bot 手持盾 USE，验证 body.isUsingItem()=true 且 activeItem 是盾',
    'combat', 'gametest',
    ['前置: bot 手持盾', '执行: USE rising edge', '观察: body.isUsingItem() 和 body.getUseItem()'],
    'body.isUsingItem()=true，activeItem 是 ShieldItem；facade.isUsingItem()=false',
    ['RM-001'], 'fixture: shield in hotbar, flat platform',
    '盾在 body 上 raise 是格挡生效的前提，在 facade 上则完全无效')
add('REQ-006', '举盾5tick后正面受击伤害减免', 'P0',
    'bot 举盾 5 tick 后正面受击，验证伤害被减免（盾挡）',
    'combat', 'gametest',
    ['前置: bot 手持盾，满血', '执行: 举盾 5 tick 后让僵尸正面攻击', '观察: bot 受到的伤害值'],
    '伤害被减免（全挡或部分挡，取决于盾牌），播放盾挡音效',
    ['RM-001', 'RM-007'], 'fixture: shield, zombie at melee range, bot full health',
    '盾伤害减免是生存核心机制，失效则盾完全无意义')
add('REQ-006', '举盾0-4tick内受击不减免', 'P2',
    'bot 刚举盾（0-4 tick）时受击，验证 isDamageSourceBlocked 返回 false',
    'combat', 'gametest',
    ['前置: bot 手持盾，满血', '执行: 举盾 2 tick 后立即受击', '观察: 伤害值'],
    '伤害全额（不减免），因为 useDuration < 5',
    ['RM-007'], 'fixture: shield, zombie, bot full health')
add('REQ-006', '背后受击不减免', 'P1',
    'bot 举盾但从背后受击，验证伤害不减免',
    'combat', 'gametest',
    ['前置: bot 举盾中', '执行: 从背后 180° 外攻击 bot', '观察: 伤害值'],
    '伤害全额（背后不在盾格挡扇形内）',
    ['RM-001'], 'fixture: shield, attacker behind bot')
add('REQ-005', '收盾后body退出use状态', 'P1',
    '盾 USE falling edge 后 body.isUsingItem()=false',
    'combat', 'gametest',
    ['前置: bot 举盾中', '执行: USE falling edge', '观察: body.isUsingItem()'],
    '收盾后 body.isUsingItem()=false',
    ['RM-001'], 'fixture: shield')

# Sleep cases
add('REQ-007', '睡眠机制存在性确认', 'P2',
    '确认 BotBodyEntity 是否有主动睡眠/起床机制，或仅继承 PathfinderMob base class',
    'sleep', 'code_audit',
    ['前置: 读取 BotBodyEntity.java 全文', '执行: 搜索 sleep/isSleeping/startSleeping/stopSleeping 相关代码', '观察: 是否有 override 或主动调用'],
    '确认睡眠机制的存在性和触发条件；若不存在则标记为 deferred',
    ['RM-008'], 'fixture: BotBodyEntity.java source code audit')

# Tool-select cases
add('REQ-008', '挖石头自动切换到镐', 'P1',
    'bot 挖掘石头时 ToolSelector 自动切换到镐（非剑/空手）',
    'dig', 'gametest',
    ['前置: hotbar 有镐+剑+空手，目标方块为石头', '执行: 提交 Dig intent', '观察: selectedSlot 是否切换到镐，DigExecutor 是否使用镐'],
    'selectedSlot 切换到镐的 slot，挖掘使用镐，掉落圆石',
    ['RM-006'], 'fixture: pickaxe at hotbar[1], sword at hotbar[2], stone target')
add('REQ-008', '挖树叶自动切换到剪刀', 'P1',
    'bot 挖掘树叶时 ToolSelector 切换到剪刀（match_tool shears）',
    'dig', 'gametest',
    ['前置: hotbar 有剪刀+镐，目标为树叶', '执行: Dig intent', '观察: selectedSlot 和掉落物'],
    '切换到剪刀，掉落树叶物品（非木棍/苹果概率）',
    ['RM-006'], 'fixture: shears at hotbar[1], leaves target')

# Rescue cases
add('REQ-009', '救援任务机制面确认', 'P2',
    '确认 RescueMissionFactory 的实际机制面、触发条件、执行流程',
    'rescue', 'code_audit',
    ['前置: 读取 RescueMissionFactory.java 全文', '执行: 搜索调用方（reflex/process）、生成逻辑、执行流程', '观察: 机制面完整性'],
    '确认救援任务是否为完整实现、stub 或半成品；标记可测试的机制面',
    ['RM-009'], 'fixture: RescueMissionFactory.java source code audit')

# Regression case
add('REQ-010', '新增场景后GametestInventoryCheck通过', 'P0',
    '新增 gametest 场景后更新 EXPECTED 钉表，GametestInventoryCheck 双向比对通过',
    'hygiene', 'unit',
    ['前置: 新增 N 个 @GameTest 场景', '执行: 更新 GametestInventoryCheck.EXPECTED，运行 test', '观察: GametestInventoryCheck 结果'],
    '测试通过，场景数 67→67+N，无 missing/unexpected',
    ['RM-010'], 'fixture: new gametest scenarios + updated EXPECTED',
    '钉表漂移是 routesThroughFenceGap 事故模式，必须阻断')

data['cases'] = cases

# === UNVERIFIED ===
data['unverified'] = [
    '睡眠机制存在性未确认（REQ-007）——需代码审计确认 BotBodyEntity 是否有主动睡眠，可能标记为 deferred',
    '救援任务机制面未确认（REQ-009）——RescueMissionFactory 可能是 stub，需代码审计后决定是否生成测试场景',
    '弓蓄力精确 tick 数与 vanilla 对齐——facade.tickUseLoop 的 useTicks 累积速率需在引擎内实测确认',
    '钓鱼咬钩的自然触发——gametest 环境中鱼的咬钩行为可能需要特殊 staging（手动移动浮漂或使用 spawn egg）',
    '盾格挡的精确伤害减免值——vanilla 盾全挡/部分挡的角度阈值和盾牌附魔影响需在引擎内实测',
    '全部 24 条测试用例当前状态为 not_executed——需编写 gametest 场景代码并在运行的 MC 实例中执行',
]

# === COVERAGE ===
data['coverage']['requirement_total'] = len(reqs)
data['coverage']['case_total'] = len(cases)
data['coverage']['p0_requirement_total'] = len([r for r in reqs if r['risk'] == 'P0'])

with open('qa-results/ranged-survival/qa-run.json', 'w', encoding='utf-8') as f:
    json.dump(data, f, ensure_ascii=False, indent=2)

print(f'Requirements: {len(reqs)}, Risk mechanisms: {len(rms)}, Cases: {len(cases)}')
print(f'P0 requirements: {data["coverage"]["p0_requirement_total"]}')
