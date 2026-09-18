from decimal import Decimal

# Authoritative Leave & Compensation Rules (System Constants)
# These are systemic rules, NOT hardcoded individual guard balances.
VACATION_ACCRUAL_RATE = Decimal("2.5")  # days per month
CASUAL_ACCRUAL_RATE = Decimal("1.0")    # days per month
MAX_VACATION_DAYS = Decimal("90.0")     # statutory vacation balance ceiling
MAX_CASUAL_DAYS_PER_CYCLE = Decimal("12.0")  # maximum casual days per 12-month cycle
PUBLIC_HOLIDAY_COMPENSATION_DAYS = Decimal("2.0")  # compensation days earned per actually worked public holiday
