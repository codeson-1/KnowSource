# KnowSource Eval Report

Generated at: 2026-06-24T14:16:43.251788900

| Metric | Value |
|---|---:|
| Total cases | 10 |
| In-scope cases | 8 |
| Out-of-scope cases | 2 |
| Recall@5 | 100.0% |
| Citation hit rate | 100.0% |
| Refusal accuracy | 100.0% |

## Case Results

| ID | Setup | Question | Expected | Refused | Source titles | Pass |
|---|---|---|---|---:|---|---:|
| leave_days |  | How many annual leave days are available? | Annual Leave Policy | false | Annual Leave Policy, Remote Work Policy | yes |
| leave_approval |  | Who must approve annual leave? | Annual Leave Policy | false | Annual Leave Policy, Remote Work Policy | yes |
| security_badge |  | What is required in the office for security? | Security Policy | false | Security Policy | yes |
| expense_deadline |  | When should reimbursement receipts be submitted? | Expense Policy | false | Expense Policy | yes |
| remote_work |  | How many remote work days are allowed each week? | Remote Work Policy | false | Remote Work Policy | yes |
| followup_leave_approval | How many annual leave days are available? | What is its approval process? | Annual Leave Policy | false | Annual Leave Policy, Remote Work Policy | yes |
| followup_remote_approval | How many remote work days are allowed each week? | Who approves it? | Remote Work Policy | false | Annual Leave Policy, Remote Work Policy | yes |
| modular_multiquery_expense | What is required in the office for security? | When should reimbursement receipts be submitted? | Expense Policy | false | Expense Policy, Security Policy | yes |
| stock_code |  | What is the company's stock ticker? | REFUSAL | true |  | yes |
| cafeteria_menu |  | What is tomorrow's cafeteria menu? | REFUSAL | true |  | yes |
