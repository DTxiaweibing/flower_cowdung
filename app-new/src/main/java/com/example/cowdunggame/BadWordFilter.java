// BadWordFilter.java
// 敏感词过滤器：封装 com.github.houbb:sensitive-word（与 shrimpfarm 项目同款依赖 v0.29.5）
//   自带大词库，支持中英文、拼音/同音字/部首拆分/中介字遮挡等强过滤。
//   用法与 shrimpfarm 的 SensitiveWordFilter 一致：单例延迟初始化。
package com.example.cowdunggame;

import com.github.houbb.sensitive.word.bs.SensitiveWordBs;

public class BadWordFilter {

    private static volatile SensitiveWordBs instance;

    private SensitiveWordBs get() {
        if (instance == null) {
            synchronized (BadWordFilter.class) {
                if (instance == null) {
                    instance = SensitiveWordBs.newInstance().init();
                }
            }
        }
        return instance;
    }

    // 是否含敏感词
    public boolean containsBadWord(String text) {
        if (text == null || text.isEmpty()) return false;
        try {
            return get().contains(text);
        } catch (Throwable t) {
            return false;
        }
    }

    // 将命中的敏感词替换为 *
    public String filter(String text) {
        if (text == null || text.isEmpty()) return text;
        try {
            return get().replace(text);
        } catch (Throwable t) {
            return text;
        }
    }
}
