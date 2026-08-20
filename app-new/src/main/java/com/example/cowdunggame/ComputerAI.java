// ComputerAI.java
// 《鲜花与牛粪》电脑 AI 模块 —— Nim 最优策略
//
// 设计双场景复用：
//   1. 人机对战：直接调用 getNextMove() 落子
//   2. 双人对战：后台机器人盯盘，开发者可随时调 getHint() / getNextMove()
//      给任意一方提示"下一步该怎么拿"
package com.example.cowdunggame;

import java.util.ArrayList;
import java.util.Random;

public class ComputerAI {

    private static final Random random = new Random();

    // 单步走法：从哪一排拿几朵花
    public static class Move {
        public final int row;    // 排索引（0 为牛粪，只能 >= 1）
        public final int count;  // 拿取数量（1 ~ 该排剩余数）
        public Move(int row, int count) {
            this.row = row;
            this.count = count;
        }
    }

    /**
     * 计算电脑的落子走法（最优 Nim 策略）。
     *
     * @param remainingFlowers 棋盘每排剩余数量，长度 6：[牛粪, 2,3,4,5,6 行]
     *                         注意力：调用方应保证 index 1~5 为可拿取的鲜花。
     * @return Move 走法；若无可拿鲜花（棋盘已结束）返回 null
     */
    public static Move getNextMove(int[] remainingFlowers) {
        int nimSum = 0;
        for (int i = 1; i < remainingFlowers.length; i++) {
            nimSum ^= remainingFlowers[i];
        }

        // 非必败局面：找必胜步
        if (nimSum != 0) {
            for (int i = 1; i < remainingFlowers.length; i++) {
                if (remainingFlowers[i] > 0) {
                    int take = remainingFlowers[i] - (remainingFlowers[i] ^ nimSum);
                    if (take > 0 && take <= remainingFlowers[i]) {
                        return new Move(i, take);
                    }
                }
            }
        }

        // 必败局面：随机走一步
        ArrayList<Integer> availableRows = new ArrayList<>();
        for (int i = 1; i < remainingFlowers.length; i++) {
            if (remainingFlowers[i] > 0) {
                availableRows.add(i);
            }
        }
        if (availableRows.isEmpty()) {
            return null; // 无棋可走
        }
        int row = availableRows.get(random.nextInt(availableRows.size()));
        int count = random.nextInt(remainingFlowers[row]) + 1;
        return new Move(row, count);
    }

    /**
     * 提示下一步走法（开发者/观战提示用，可任意时刻调用）。
     *
     * @param remainingFlowers 棋盘当前状态
     * @return Move 走法；无棋可走返回 null
     */
    public static Move getHint(int[] remainingFlowers) {
        return getNextMove(remainingFlowers);
    }
}