package com.smartfridge.app.domain

/**
 * 保鲜表 v1（与 DS prompt / 演示规则同源）：
 * 同一食材换分区时自动重算保质期；未收录食材返回 null（保持原值）。
 * 数值校准来源：香港食物安全中心《冰箱与冷柜的食品储存》(CFS) 系列 +
 * 湖北省卫健委《食物的储存和解冻》科普；分类框架对齐《中国居民膳食指南》膳食宝塔。
 */
object FreshnessTable {

    private val table = listOf(
        // name正则 -> (FRIDGE天, FREEZER天, PANTRY天)   null=该区不推荐(保持原值)
        Regex("猪|牛|羊|肉|里脊|排骨") to Triple(4, 365, null),          // 生鲜红肉
        Regex("鸡|鸭|鹅|禽") to Triple(2, 300, null),                    // 生鲜禽肉
        Regex("香肠|培根|火腿|腊肠|腊肉|午餐肉") to Triple(90, 180, null),// 加工肉(密封包装按标注, 冷藏 90 兜底)
        Regex("蛋") to Triple(30, 180, null),                            // 蛋类(鸡蛋30, 咸蛋/皮蛋冷存更长)
        Regex("盐|糖|油|酱|醋|料酒|蚝油|味精|鸡精|胡椒|花椒|八角|香叶|桂皮|孜然|咖喱|蜂蜜|生抽|老抽") to Triple(null, null, 365), // 调味
        Regex("虾皮|海米|干贝|紫菜|海带干|干贝") to Triple(null, null, 365), // 干海产
        Regex("鱼|虾|蟹|贝|蛤|蛏|鱿|章|海参|生蚝|鲍|龙虾") to Triple(2, 180, null), // 海鲜水产
        // 耐储蔬菜(2026-09-05 校准): 白菜/包菜等冷藏可放 2-4 周; 放在叶菜规则之前(先匹配先得)
        Regex("(?<!小)白菜|大白菜|圆白菜|包菜|卷心菜|甘蓝|花菜|菜花") to Triple(14, 270, null),
        // 新鲜香草调味(校准: 香菜/小葱冷藏仅约一周, 严禁跟大蒜一样给 60 天)
        Regex("小葱|香葱|香菜|青蒜") to Triple(7, null, 3),
        Regex("菜|菠菜|生菜|油菜|芹菜|韭菜|莴笋|上海青|小白菜|娃娃菜|油麦菜|苋菜|空心菜|茼蒿|苦菊|芥蓝|芦笋|西兰花|芥菜蒜苗|蒜薹|豆苗") to Triple(5, 270, null), // 鲜叶菜
        Regex("番茄|西红柿|圣女果|小番茄|青椒|辣椒|彩椒|甜椒|菜椒|秋葵|黄瓜|冬瓜|丝瓜|苦瓜|西葫芦|茄子") to Triple(7, 180, 3), // 茄果瓜
        Regex("胡萝卜|白萝卜|山药|莲藕|藕|荸荠|菱角") to Triple(30, null, 30), // 根茎(冷藏 3-6 周)
        Regex("土豆|红薯|紫薯|芋头|香芋|南瓜") to Triple(null, null, 60),   // 耐储薯芋瓜→常温
        Regex("洋葱|大葱|生姜") to Triple(null, null, 60),                   // 洋葱/大葱/姜 阴凉 1-2 个月
        Regex("大蒜|蒜头") to Triple(null, null, 90),                        // 大蒜阴凉通风可放数月
        Regex("蘑菇|香菇|金针菇|杏鲍菇|平菇|木耳|银耳|茶树菇|海鲜菇|蟹味菇|白玉菇|鸡腿菇|口蘑|草菇|猴头菇") to Triple(5, 180, 90), // 菌菇
        Regex("西瓜|哈密瓜|甜瓜|木瓜") to Triple(4, null, 7),            // 大瓜果
        Regex("草莓|蓝莓|樱桃|葡萄|提子|杨梅|桑葚|莓") to Triple(6, 180, 2),  // 浆果
        Regex("香蕉|芒果|菠萝|榴莲|牛油果|荔枝|山竹|火龙果|百香果") to Triple(4, 180, 5), // 热带
        Regex("苹果|梨|桃|李子|柚子|橙|橘|柿子|石榴|山楂|柠檬|枣|枸杞") to Triple(14, null, 4), // 核果
        Regex("玉米|玉米粒") to Triple(7, 365, 2),                       // 玉米（冷冻 365）
        Regex("米|面粉|挂面|面条|燕麦|粉丝|粉条|腐竹") to Triple(null, null, 180), // 干粮
        Regex("面包|蛋糕|饼干|月饼|点心|曲奇") to Triple(4, null, 7),    // 面包糕点
        Regex("饺子|馄饨|包子|汤圆|年糕|粽子|烧麦") to Triple(3, 90, null), // 速冻面点
        Regex("瓜子|花生|核桃|坚果|开心果|腰果|板栗|甘栗") to Triple(null, 180, 120), // 坚果种子
        Regex("奶茶|可乐|雪碧|咖啡|茶|啤酒|果汁|饮料|汽水") to Triple(null, null, 180), // 饮品
        Regex("奶|牛奶|酸奶|酪|芝士|黄油|奶油") to Triple(6, 60, null), // 乳品
        Regex("豆腐|豆干|腐竹|纳豆") to Triple(4, 90, null),             // 豆制品
        Regex("豆芽|黄豆芽|绿豆芽|豌豆苗|豆苗") to Triple(2, null, null),// 豆芽
        Regex("泡菜|咸菜|榨菜|腐乳|酸菜|酱菜") to Triple(30, 180, 90),   // 腌制品
        Regex("冻|冰") to Triple(null, 365, null),                       // 明确冷冻品
    )

    /** 按食材名+分区给默认保质天数；未收录/不推荐返回 null（保持原值） */
    fun daysFor(name: String, zone: String): Int? {
        val z = when (zone) {
            "FRIDGE" -> 0
            "FREEZER" -> 1
            "PANTRY" -> 2
            else -> return null
        }
        for ((re, days) in table) {
            if (re.containsMatchIn(name)) {
                return when (z) {
                    0 -> days.first
                    1 -> days.second
                    else -> days.third
                }
            }
        }
        return null
    }
}
