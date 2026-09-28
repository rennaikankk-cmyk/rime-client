package dev.guizhou.features.settings;

import dev.guizhou.features.modules.Module;

import java.util.Map;

/** One-time Chinese blurbs. Stored on the setting so the GUI does not build them per frame. */
public final class SettingText {
    private SettingText() {}

    public static void fill(Module module) {
        String type = module.getClass().getSimpleName();
        for (Setting<?> setting : module.getSettings()) {
            if (!setting.getDescription().isBlank()) continue;
            String text = specific(type, setting.getName());
            if (text == null) text = generic(setting);
            setting.described(text);
        }
    }

    private static String specific(String type, String name) {
        Map<String, String> table = TABLES.get(type);
        return table == null ? null : table.get(name);
    }

    private static String generic(Setting<?> setting) {
        String name = setting.getName();
        if (name.equals("Enabled")) return "打开或关闭这个模块。";
        if (name.equals("Keybind")) return "这个模块的快捷键。没有绑定时不响应按键。";
        if (name.equals("BindMode")) return "快捷键是切换开关，还是按住才开启。";
        if (name.equals("DisplayName")) return "界面上显示的模块名字。配置键仍用原来的名字。";
        if (setting.isNumberSetting() && setting.hasRestriction()) return "「" + name + "」的数值，范围是滑条两端。";
        if (setting.isEnumSetting()) return "在列出的选项里选择「" + name + "」的工作方式。";
        if (setting.isColorSetting()) return "「" + name + "」使用的颜色。";
        if (setting.getValue() instanceof Boolean) return "打开或关闭「" + name + "」。";
        if (setting.getValue() instanceof Bind) return "「" + name + "」的按键。";
        return "调整「" + name + "」。";
    }

    private static final Map<String, Map<String, String>> TABLES = Map.ofEntries(
            Map.entry("ClickGuiModule", Map.ofEntries(
                    Map.entry("Prefix", "聊天命令的默认前缀。旧前缀仍能输入。"),
                    Map.entry("Style", "界面样式：紫色面板、蓝框面板或现代卡片。"),
                    Map.entry("BlurBackground", "保留的开关。打开界面时不再抓整屏做模糊，世界只压暗。"),
                    Map.entry("Descriptions", "是否显示模块说明。设置项说明仍会写在设置行上。"),
                    Map.entry("1.12协议", "强制使用 1.12 的旧位置/旋转包、双视角和已有 1.12 放置逻辑。默认关闭，现代服和 Grim 仍走自动检测。"),
                    Map.entry("MenuNotice", "标题画面卡片底部的一句提示。留空则显示欢迎回来。"),
                    Map.entry("MenuWatermark", "标题画面中央的浅色大字。留空则不画。"),
                    Map.entry("Theme", "面板和模块名字使用的配色。"),
                    Map.entry("Custom Color", "主题选自定义时使用的颜色。"),
                    Map.entry("Smooth", "开关和滑条是否用短动画过渡。"),
                    Map.entry("菜单大小 (%)", "ClickGUI 和标题画面的缩放。"),
                    Map.entry("Delay", "彩虹主题变色的间隔。"),
                    Map.entry("Brightness", "彩虹主题的亮度。"),
                    Map.entry("Saturation", "彩虹主题的饱和度。"),
                    Map.entry("ClickGUI Font", "界面是否改用自定义字体。"),
                    Map.entry("HUD Font", "HUD 是否改用自定义字体。"),
                    Map.entry("Font Name", "自定义字体的名字。留空则用内置字体。"),
                    Map.entry("Favorites", "收藏的模块名单，由界面写入。"),
                    Map.entry("PanelLayout", "面板位置和折叠状态，由界面写入。")
            )),
            Map.entry("GuangxiCrystalModule", Map.ofEntries(
                    Map.entry("Place", "允许自动放置末影水晶。"),
                    Map.entry("PlaceDelay", "两次放水晶之间的等待，单位毫秒。"),
                    Map.entry("Break", "允许攻击已经放好的水晶。"),
                    Map.entry("BreakDelay", "两次炸水晶之间的等待，单位毫秒。"),
                    Map.entry("MinDamage", "低于这个伤害的位置不会选。"),
                    Map.entry("MaxSelfDamage", "预估自伤超过这个值就不放、不炸。"),
                    Map.entry("Reserve", "给自己留出的生命值，避免打到这个血量以下。"),
                    Map.entry("Balance", "比较对目标和自己的伤害，避免亏打。"),
                    Map.entry("BalanceOffset", "伤害比较时额外偏向目标或自己。"),
                    Map.entry("AntiSurround", "联动挖掘评估开墙后的伤害；DEFAULT 的目标伤害等待方块确认消失。"),
                    Map.entry("AntiSurroundCompletion", "围墙挖开到这个百分比后才把位置算进去。"),
                    Map.entry("DamageSync", "按受到的伤害节奏放水晶，而不是连续放。"),
                    Map.entry("FacePlace", "目标血量或护甲很低时，允许放在脸上。"),
                    Map.entry("EntityOverlap", "允许和生物重叠的位置。默认关闭。"),
                    Map.entry("FacePlaceHealth", "目标血量低于这个值时允许脸放。"),
                    Map.entry("FacePlaceArmor", "目标护甲件数少到这个数时允许脸放。"),
                    Map.entry("SneakPlace", "放置时按下潜行。"),
                    Map.entry("WallPlace", "隔墙时仍允许在更短的距离内放置。"),
                    Map.entry("WallRange", "隔墙放置的最远距离。"),
                    Map.entry("AutoSwap", "手里没有水晶时怎么换到水晶。静默换不改你看见的物品。"),
                    Map.entry("Rotate", "放置和攻击的同一拍直接发转头包，不等待转头队列。"),
                    Map.entry("Logic", "Default 同一拍可以先炸再放。Rusher 有可炸水晶时这一拍只炸。虚弱且主手不是剑时先换到快捷栏的剑。"),
                    Map.entry("Predict", "按目标当前速度预测接下来的位置。"),
                    Map.entry("AdaptivePredict", "DEFAULT 按延迟缩短预测；高速移动时自动检查沿途自伤。"),
                    Map.entry("PredictTicks", "向前预测的刻数；自适应开启时作为上限。"),
                    Map.entry("MaxMotionY", "限制预测的上升速度；DEFAULT 鞘翅飞行使用实际速度。"),
                    Map.entry("SelfPredict", "自伤预测刻数。设为 0 时，自适应仍检查高速移动；关闭自适应可禁用。"),
                    Map.entry("HurtSync", "等自己刚受到伤害后再出手。"),
                    Map.entry("WaitHurt", "等待受伤同步的最长时间，单位是刻。"),
                    Map.entry("SyncTimeout", "同步等不到就放弃这一轮，单位毫秒。"),
                    Map.entry("SwitchPause", "刚切过物品后暂停出手的时间，单位毫秒。"),
                    Map.entry("MinAge", "水晶出现后至少过这么多刻才攻击。"),
                    Map.entry("TpsSync", "服务器卡顿时按实际 TPS 拉长间隔。"),
                    Map.entry("GhostHit", "水晶实体还没同步时，按猜测的编号补一次攻击。默认关闭。"),
                    Map.entry("GhostFactor", "猜测编号相对本地放置的偏移。"),
                    Map.entry("1.12", "按 1.12 的头顶空间检查能不能放水晶。"),
                    Map.entry("AntiChinese", "近距离时改用另一套更短的放置距离。"),
                    Map.entry("Range", "近距离模式自己的放置距离。"),
                    Map.entry("EnemyRange", "敌人进入这个距离才使用近距离模式。"),
                    Map.entry("BasePlace", "脚下没有黑曜石时先补一块底座。"),
                    Map.entry("BasePlaceEnemyRange", "只给这个距离内的目标补底座。"),
                    Map.entry("BasePlaceMinDamage", "补底座后预计伤害要达到这个值。"),
                    Map.entry("BaseOverrideMax", "已有位置伤害低于这个值时，才改去补底座。"),
                    Map.entry("BaseMaxSelfDamage", "补底座允许的自伤上限。"),
                    Map.entry("BaseBalance", "补底座时也做对自己和目标的伤害比较。"),
                    Map.entry("DetectMining", "正在挖的方块不拿来当底座。"),
                    Map.entry("Debug", "在聊天里打出选择原因。"),
                    Map.entry("PacketLog", "记录放置和攻击包有没有发出。"),
                    Map.entry("Render", "准备放置的位置怎么画出来。"),
                    Map.entry("SideColor", "位置方框的填充色。"),
                    Map.entry("LineColor", "位置方框的边线色。"),
                    Map.entry("LineWidth", "边线粗细。"),
                    Map.entry("GradientHeight", "渐变填充占方块高度的比例。"),
                    Map.entry("RenderTime", "位置标记保留多少刻。"),
                    Map.entry("Smoothness", "标记出现和消失的平滑程度。"),
                    Map.entry("PulseColor", "脉冲圈的颜色。"),
                    Map.entry("PulseSpeed", "脉冲圈扩散的速度。"),
                    Map.entry("PulseExpand", "脉冲圈向外扩多大。"),
                    Map.entry("DamageText", "在放置方框上写出这次算出的伤害。"),
                    Map.entry("DamageColor", "伤害数字的颜色。"),
                    Map.entry("RangePreset", "放置和爆炸距离用哪一套预设。"),
                    Map.entry("PlaceRange", "最远放水晶的距离。"),
                    Map.entry("BreakRange", "最远炸水晶的距离。"),
                    Map.entry("BlockDestruction", "考虑低爆抗方块消失后的伤害。DEFAULT 仅用于自伤风险，目标伤害按当前遮挡计算。"),
                    Map.entry("Inhibit", "同一颗水晶在确认前不要连续攻击。"),
                    Map.entry("AttackLimit", "一颗水晶最多补打几次。"),
                    Map.entry("BreakTimeout", "攻击发出后多久没确认就放弃，单位毫秒。"),
                    Map.entry("RetryReset", "失败次数过多时，隔这么久再允许重试。"),
                    Map.entry("PlaceTimeout", "放置发出后多久没看到水晶就放弃，单位毫秒。"),
                    Map.entry("ReactiveScans", "方块变化后，这一拍最多再搜几次位置。")
            )),
            Map.entry("GuangxiTPModule", Map.ofEntries(
                    Map.entry("Range", "目标离自己超过这个距离才考虑瞬移放水晶。"),
                    Map.entry("Obsidian", "没有底座时先放黑曜石。"),
                    Map.entry("Rotate", "假移动包对准点击点或水晶。关掉则用服务器当前朝向。"),
                    Map.entry("Delay", "一次瞬移放晶完成后，隔这么久才能再来。")
            )),
            Map.entry("GuangxiPistonModule", Map.ofEntries(
                    Map.entry("AirPlace", "打开后才允许没有支撑面的空中放置。默认先找真实方块。"),
                    Map.entry("Delay", "活塞、红石和水晶各步之间的等待。"),
                    Map.entry("Range", "活塞水晶的最远作用距离。"),
                    Map.entry("InventorySwap", "快捷栏没有活塞、红石、黑曜石或水晶时，从背包静默换过来。")
            )),
            Map.entry("AutoAnchorModule", Map.ofEntries(
                    Map.entry("Place", "允许放置重生锚。"),
                    Map.entry("Charge", "允许给锚充能。"),
                    Map.entry("Explode", "允许引爆锚。"),
                    Map.entry("MinDamage", "低于这个伤害的位置不用。"),
                    Map.entry("MaxSelfDamage", "自伤超过这个值就不用这个位置。"),
                    Map.entry("Range", "锚的最远作用距离。更远的格子会先丢掉。")
            )),
            Map.entry("GuangxiBedModule", Map.ofEntries(
                    Map.entry("Place", "在能炸床的维度放置床。"),
                    Map.entry("Break", "放置后引爆床。"),
                    Map.entry("MinDamage", "低于这个伤害的床位不用。"),
                    Map.entry("MaxSelfDamage", "自伤超过这个值就不放床。"),
                    Map.entry("Rotate", "放置前把朝向转到床。瞬移床还会带上俯仰。")
            )),
            Map.entry("AutoMineModule", Map.ofEntries(
                    Map.entry("Range", "自动挖掘考虑的最远距离。"),
                    Map.entry("Rotate", "挖掘前把朝向转到方块。")
            )),
            Map.entry("GuangxiMineModule", Map.ofEntries(
                    Map.entry("Range", "发包挖掘的最远距离。"),
                    Map.entry("Double", "允许两格一起挖。")
            )),
            Map.entry("AutoSwordModule", Map.ofEntries(
                    Map.entry("Range", "自动剑会考虑的最远目标。"),
                    Map.entry("Rotate", "攻击前把朝向转到目标。"),
                    Map.entry("Players", "只打玩家。"),
                    Map.entry("Delay", "两次挥剑之间的等待。")
            )),
            Map.entry("KillAuraModule", Map.ofEntries(
                    Map.entry("Targets", "冷却好时这一拍最多换几个目标，范围是 1 到 3。"),
                    Map.entry("Range", "沿用自动剑的距离判断。"),
                    Map.entry("Rotate", "攻击前转到目标。"),
                    Map.entry("Players", "只打玩家。")
            )),
            Map.entry("AutoTotemModule", Map.ofEntries(
                    Map.entry("Item", "平时副手想拿的物品。默认是图腾。"),
                    Map.entry("Health", "血量低于这个值时强制把副手换成图腾。"),
                    Map.entry("SafeHealth", "血量高于这个值，才允许为金苹果或战斗物品让出图腾。"),
                    Map.entry("Delay", "两次换副手之间的等待，单位是刻。"),
                    Map.entry("GappleOnUse", "按住使用键、主手是剑斧水晶或图腾时，副手换成金苹果。"),
                    Map.entry("Crapple", "没有金苹果时允许用附魔金苹果。"),
                    Map.entry("CombatSync", "水晶、床或锚要出手时，先把副手换成对应物品。"),
                    Map.entry("Lethal", "预测到致命伤害时提前换上图腾。"),
                    Map.entry("LethalMargin", "预测伤害比当前血量高出这么多，才算致命。"),
                    Map.entry("Crystals", "把附近水晶算进致命预测。"),
                    Map.entry("CrystalSpots", "把还能放水晶的位置算进致命预测。"),
                    Map.entry("AnchorsBeds", "把锚和床算进致命预测。"),
                    Map.entry("Fall", "把摔落伤害算进致命预测。"),
                    Map.entry("Elytra", "滑翔时也做致命预测。"),
                    Map.entry("Melee", "把近战伤害算进致命预测。"),
                    Map.entry("EnemyRange", "只关心这个距离内的敌人。")
            ))
    );
}
