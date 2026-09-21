package com.gptr.worker.budget;

/**
 * 预算超限异常：步骤/时长/成本任一超限，任务应优雅终止
 * （保留中间结果，置 FAILED + BUDGET_&lt;KIND&gt;）。
 */
public class BudgetExceededException extends RuntimeException {

    /** 超限预算类型：STEP / TIME / COST。 */
    private final String budgetKind;

    public BudgetExceededException(String budgetKind) {
        super("budget " + budgetKind + " exceeded");
        this.budgetKind = budgetKind;
    }

    public String getBudgetKind() {
        return budgetKind;
    }
}
