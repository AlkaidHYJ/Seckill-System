package com.example.flashsale.enums;

/**
 * 对账结论，沿用 reconciliation_record.result 的 DDL tinyint 口径：
 * 0 一致、1 疑似超卖（diff &lt; 0）、2 疑似少卖（diff &gt; 0）。
 * 设计文档 17.4 的符号值 CONSISTENT/COMPENSATING 中，CONSISTENT 映射 code=0；
 * COMPENSATING（在途豁免）不占用独立 code，以 detail 标注 + 不告警表达。
 */
public enum ReconciliationResult {

    CONSISTENT(0, "一致"),
    OVERSOLD(1, "疑似超卖"),
    UNDERSOLD(2, "疑似少卖");

    private final int code;
    private final String label;

    ReconciliationResult(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }
}