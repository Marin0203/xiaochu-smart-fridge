package com.smartfridge.app.ai

import com.smartfridge.app.domain.ExpiringItem
import com.smartfridge.app.domain.IngredientLine
import com.smartfridge.app.domain.Recipe
import com.smartfridge.app.domain.RecipePlan

/**
 * 本地降级菜谱 (v0.10 清单 §10 对齐): AI 服务不可用/断网时展示的 5 道固定菜谱,
 * 名称/标签/步骤/默认用量 与种子数据 1:1 (C-150~C-159)。
 */
fun localFallbackRecipes(expiring: List<ExpiringItem>): RecipePlan {
    val recipes = listOf(
        Recipe(
            title = "番茄牛腩煲",
            minutes = 40,
            uses = listOf("番茄", "牛排", "洋葱"),
            ingredients = listOf(
                IngredientLine("番茄", "3 个"),
                IngredientLine("牛排", "250 克"),
                IngredientLine("洋葱", "1 个"),
            ),
            steps = listOf(
                "牛排切块，冷水下锅焯至浮沫，捞出沥干",
                "番茄划十字烫去皮，切块；洋葱切丝",
                "热油煸香洋葱，下番茄炒出沙，加开水烧开",
                "下牛排转小火炖 40 分钟，盐调味，出锅撒葱花",
            ),
            tips = "本菜谱由本地规则生成 (AI 服务暂时不可用)",
        ),
        Recipe(
            title = "滑蛋虾仁",
            minutes = 15,
            uses = listOf("鸡蛋", "虾仁"),
            ingredients = listOf(
                IngredientLine("虾仁", "200 克"),
                IngredientLine("鸡蛋", "4 个"),
            ),
            steps = listOf(
                "虾仁解冻，料酒腌 5 分钟去腥",
                "鸡蛋打散，加少许盐和温水",
                "宽油滑熟虾仁，倒入蛋液小火轻推至半凝固，关火焖 10 秒",
            ),
            tips = "本菜谱由本地规则生成 (AI 服务暂时不可用)",
        ),
        Recipe(
            title = "手撕凉拌生菜",
            minutes = 10,
            uses = listOf("生菜", "蒜"),
            ingredients = listOf(
                IngredientLine("生菜", "1 颗"),
                IngredientLine("蒜", "4 瓣"),
            ),
            steps = listOf(
                "生菜洗净沥干，手撕成片",
                "蒜末热油泼香，生抽、香醋、糖调汁",
                "淋在生菜上拌匀，撒芝麻上桌",
            ),
            tips = "本菜谱由本地规则生成 (AI 服务暂时不可用)",
        ),
        Recipe(
            title = "玉米浓汤",
            minutes = 25,
            uses = listOf("玉米粒", "牛奶"),
            ingredients = listOf(
                IngredientLine("玉米粒", "200 克"),
                IngredientLine("牛奶", "1 盒"),
            ),
            steps = listOf(
                "玉米粒解冻，一半打成玉米浆",
                "牛奶加玉米浆小火煮开，搅拌防糊底",
                "倒入余下玉米粒煮 5 分钟，盐、黑胡椒调味",
            ),
            tips = "本菜谱由本地规则生成 (AI 服务暂时不可用)",
        ),
        Recipe(
            title = "蜂蜜烤苹果",
            minutes = 20,
            uses = listOf("苹果", "蜂蜜"),
            ingredients = listOf(
                IngredientLine("苹果", "2 个"),
                IngredientLine("蜂蜜", "2 勺"),
            ),
            steps = listOf(
                "苹果去核，切成一厘米厚的圆片",
                "两面刷满蜂蜜，平底锅小火煎至金黄",
                "出锅前撒上芝麻和肉桂粉",
            ),
            tips = "本菜谱由本地规则生成 (AI 服务暂时不可用)",
        ),
    )
    return RecipePlan.fallback(recipes)
}
