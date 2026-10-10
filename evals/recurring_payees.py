"""Eval: does Jev tell recurring payees from one-off ones, from a payee's charges?

Asks the payee-level question planned for recurring detection: one request per payee, with
every charge the user has made to it. Checks two things the design has to get right:

- Obvious bills and subscriptions (Netflix, Spotify, Geico...) are recurring from a single
  charge, and stay recurring however many charges there are, so users don't wait months.
- Payees whose name says nothing (rent paid to "Sterling Group") are caught once the same
  amount repeats, while frequent shopping (coffee, groceries, Amazon) is not.
- Each bill lands on the right plan line, from the same request, even when Plaid's category
  is wrong (rent labelled home improvement).

With --batch N, asks about N payees per request instead of one. The state is empty and each
question carries its own payee and charges, so it reads what a request of its own shows; see
evals/bucket_sorting.py for why that shape and not every payee in the state.

Run from the repo root; reads TYPESAFE_API_KEY from the environment or server/.env:

    python3 evals/recurring_payees.py [--batch 25]

Ambiguous cases (expected None) are printed but not scored.
"""
import argparse
import json
import os
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta

THRESHOLD = 0.5


def api_key():
    if os.environ.get("TYPESAFE_API_KEY"):
        return os.environ["TYPESAFE_API_KEY"]
    env = os.path.join(os.path.dirname(__file__), "..", "server", ".env")
    for line in open(env):
        if line.startswith("TYPESAFE_API_KEY="):
            return line.split("=", 1)[1].strip()
    sys.exit("TYPESAFE_API_KEY isn't set")


QUESTION = {
    "type": "noul",
    "instructions": {
        "question": "Does the user pay `payee` for a bill or subscription on a regular schedule?",
        "guidance": "`charges` lists the user's charges from this payee, newest first. A well-known "
        "subscription or bill is recurring even from a single charge. Otherwise, the same or a "
        "similar amount at a steady interval suggests a bill, while charges of varying amounts at "
        "irregular times suggest shopping.",
    },
    "criteria": {
        "true": "A bill or subscription charged on a schedule: rent, utilities, phone, internet, "
        "insurance, loan payments, streaming, software, memberships or gym fees.",
        "false": "One-off or occasional purchases, even from a store the user visits often: "
        "shopping, groceries, eating out, travel, cash withdrawals or payments to a person.",
    },
}

PAY_QUESTION = {
    "type": "noul",
    "instructions": {
        "question": "Does `payee` pay the user wages from a job on a regular schedule?",
        "guidance": "`deposits` lists the money the user received from this payee, newest first. "
        "Payroll from an employer is pay even from a single deposit.",
    },
    "criteria": {
        "true": "Salary or wages from an employer, paid by payroll or direct deposit.",
        "false": "Other money coming in: refunds, interest, tax refunds, reimbursements, money from "
        "other people or one-off payments.",
    },
}

# The spreadsheet's default lines, as plan setup starts with them.
PLAN_LINES = {
    "fixed costs": ["Rent/mortgage", "Utilities", "Insurance", "Car payment", "Debt payments",
                    "Groceries", "Clothes", "Phone", "Internet", "Subscriptions"],
    "investments": ["401(k)", "Roth IRA", "Other investments"],
    "savings": ["Emergency fund", "Vacations", "Gifts", "House down payment"],
}

# What each bucket holds, so lines with bare names still say what goes in them.
BUCKET_MEANINGS = {
    "fixed costs": "bills and necessities",
    "investments": "money moved into an investment or retirement account",
    "savings": "money moved into a savings account for a goal",
}

LINE_CRITERIA = {
    f"{bucket}: {line}": f"The {line} line, for {BUCKET_MEANINGS[bucket]}"
    for bucket, lines in PLAN_LINES.items() for line in lines
}
LINE_CRITERIA["none"] = ("Fits none of the lines: money moved between the user's own checking accounts, "
                         "credit card payments, payments to friends or family, or spending no line names, "
                         "such as eating out or general shopping.")

LINE_QUESTION = {
    "type": "choice",
    "instructions": {
        "question": "If the user pays `payee` regularly, which line of their Conscious Spending Plan do "
        "those payments belong under?",
        "guidance": "Judge from who is paid, how much and how often; `plaid_category` is the bank's "
        "automatic guess and is often wrong for bills paid by bank transfer. A large monthly payment to "
        "a company that is not clearly something else is usually rent paid to a landlord or property "
        "manager.",
    },
    "criteria": LINE_CRITERIA,
}

TODAY = date(2026, 9, 30)


def charges(description, amounts, every_days, category, channel="online", start=TODAY):
    """Charges newest first, `every_days` apart; `amounts` is one amount or one per charge."""
    if isinstance(amounts, (int, float)):
        amounts = [amounts]
    return [
        {
            "date": (start - timedelta(days=round(i * every_days))).isoformat(),
            "description": description,
            "amount_usd": f"{amount:.2f}",
            "plaid_category": category,
            "payment_channel": channel,
        }
        for i, amount in enumerate(amounts)
    ]


def monthly(description, amount, count, category, channel="online"):
    return charges(description, [amount] * count, 30.4, category, channel)


# Where each bill belongs; scored only for payees listed here.
LINES = {
    "Netflix": "fixed costs: Subscriptions", "Spotify": "fixed costs: Subscriptions",
    "Hulu": "fixed costs: Subscriptions", "Disney+": "fixed costs: Subscriptions",
    "YouTube Premium": "fixed costs: Subscriptions", "OpenAI": "fixed costs: Subscriptions",
    "Apple": "fixed costs: Subscriptions", "Google One": "fixed costs: Subscriptions",
    "Microsoft 365": "fixed costs: Subscriptions", "Adobe": "fixed costs: Subscriptions",
    "Amazon Prime": "fixed costs: Subscriptions", "New York Times": "fixed costs: Subscriptions",
    "Planet Fitness": "fixed costs: Subscriptions", "GEICO": "fixed costs: Insurance",
    "Progressive": "fixed costs: Insurance", "Xfinity": "fixed costs: Internet",
    "T-Mobile": "fixed costs: Phone", "Duke Energy": "fixed costs: Utilities",
    "City of Austin": "fixed costs: Utilities", "Chase": "fixed costs: Car payment",
    "Avail": "fixed costs: Rent/mortgage", "Sterling Group": "fixed costs: Rent/mortgage",
    # Transfers Plaid often detects as recurring: they need a line even though they aren't bills.
    "Ally savings": "savings: Emergency fund", "Vanguard": "investments: Other investments",
    "Robinhood": "investments: Other investments", "Fidelity Roth IRA": "investments: Roth IRA",
    "Joint checking": "none",
}

# Where everyday spending belongs. Plan setup shows what the user spends on each line, so
# shopping a line names goes there and the rest goes on none.
SPENDING_LINES = {
    "Whole Foods": "fixed costs: Groceries", "Trader Joe's": "fixed costs: Groceries",
    "Kroger": "fixed costs: Groceries", "Uniqlo": "fixed costs: Clothes",
    "Old Navy": "fixed costs: Clothes", "Starbucks": "none", "Chipotle": "none",
    "Amazon": "none", "The Home Depot": "none", "IKEA": "none", "Best Buy": "none",
    "Uber": "none", "Venmo": "none",
}

# (expected, payee, charges)
CASES = [
    # Obvious subscriptions and bills, from one charge.
    (True, "Netflix", monthly("NETFLIX.COM", 15.49, 1, "entertainment: tv and movies")),
    (True, "Spotify", monthly("Spotify USA", 11.99, 1, "entertainment: music and audio")),
    (True, "Hulu", monthly("HULU 877-8244858", 17.99, 1, "entertainment: tv and movies")),
    (True, "Disney+", monthly("DISNEY PLUS", 13.99, 1, "entertainment: tv and movies")),
    (True, "YouTube Premium", monthly("GOOGLE *YouTubePremium", 13.99, 1, "entertainment: tv and movies")),
    (True, "OpenAI", monthly("OPENAI *CHATGPT SUBSCR", 20.00, 1, "general merchandise: software")),
    (True, "Apple", monthly("APPLE.COM/BILL", 2.99, 1, "general merchandise: electronics")),
    (True, "Google One", monthly("GOOGLE *Google One", 1.99, 1, "general services: other general services")),
    (True, "Microsoft 365", monthly("MICROSOFT*MICROSOFT 365", 9.99, 1, "general merchandise: software")),
    (True, "Adobe", monthly("ADOBE *CREATIVE CLD", 59.99, 1, "general merchandise: software")),
    (True, "Amazon Prime", monthly("Amazon Prime*2K4", 14.99, 1, "general merchandise: online marketplaces")),
    (True, "New York Times", monthly("NYTIMES*NYTIMES DIGITAL", 4.00, 1, "entertainment: other entertainment")),
    (True, "Planet Fitness", monthly("Planet Fitness", 24.99, 1, "personal care: gyms and fitness centers", "in store")),
    (True, "GEICO", monthly("GEICO AUTO PAY", 134.20, 1, "general services: insurance")),
    (True, "Progressive", monthly("PROGRESSIVE INS", 98.40, 1, "general services: insurance")),
    (True, "Xfinity", monthly("COMCAST XFINITY", 79.99, 1, "rent and utilities: internet and cable")),
    (True, "T-Mobile", monthly("T-MOBILE AUTOPAY", 70.00, 1, "rent and utilities: telephone")),
    (True, "Duke Energy", monthly("DUKE ENERGY PAYMENT", 104.37, 1, "rent and utilities: gas and electricity")),
    (True, "Chase", monthly("CHASE AUTO LOAN PMT", 412.00, 1, "loan payments: car payment")),
    (True, "Avail", monthly("AVAIL RENT PAYMENT", 1850.00, 1, "general services: other general services")),
    # Netflix stays recurring however many charges, and when Plaid mislabels it.
    (True, "Netflix", monthly("NETFLIX.COM", 15.49, 3, "entertainment: tv and movies")),
    (True, "Netflix", monthly("NETFLIX.COM", 15.49, 12, "entertainment: tv and movies")),
    (True, "Netflix", charges("NETFLIX.COM", [17.99, 17.99, 15.49, 15.49], 30.4, "entertainment: tv and movies")),
    (True, "Netflix", monthly("NETFLIX.COM", 15.49, 1, "general merchandise: other general merchandise")),
    (True, "Spotify", monthly("Spotify USA", 11.99, 12, "entertainment: music and audio")),
    # Payees whose name says nothing: caught once the amount repeats.
    (True, "Sterling Group", monthly("STERLING GROUP WEB PMTS", 1554.91, 2, "home improvement: repair and maintenance")),
    (True, "Sterling Group", monthly("STERLING GROUP WEB PMTS", 1554.91, 3, "home improvement: repair and maintenance")),
    (True, "City of Austin", charges("CITY OF AUSTIN UTILITIES", [88.10, 92.40], 30.4, "rent and utilities: water")),
    # One-offs and shopping, however often.
    (False, "The Home Depot", charges("THE HOME DEPOT #1234", [86.42, 23.10, 140.77], 17, "home improvement: hardware", "in store")),
    (False, "IKEA", monthly("IKEA", 349.00, 1, "home improvement: furniture", "in store")),
    (False, "Bob's Plumbing", monthly("SQ *BOBS PLUMBING", 275.00, 1, "home improvement: repair and maintenance")),
    (False, "Amazon", charges("AMAZON MKTPL*2K4", [43.17, 12.99, 88.40, 25.00], 11, "general merchandise: online marketplaces")),
    (False, "Target", monthly("TARGET 00012", 61.20, 1, "general merchandise: superstores", "in store")),
    (False, "Whole Foods", charges("WHOLEFDS MKT", [97.54, 102.10, 88.35, 110.02, 95.40], 7, "food and drink: groceries", "in store")),
    (False, "Trader Joe's", charges("TRADER JOE S #552", [54.12, 61.80, 47.33], 9, "food and drink: groceries", "in store")),
    (False, "Kroger", monthly("KROGER #412", 132.48, 1, "food and drink: groceries", "in store")),
    (False, "Uniqlo", charges("UNIQLO USA", [59.80, 39.90], 40, "general merchandise: clothing and accessories", "in store")),
    (False, "Old Navy", monthly("OLDNAVY.COM", 84.50, 1, "general merchandise: clothing and accessories")),
    (False, "Starbucks", charges("STARBUCKS STORE 123", [6.45] * 10, 2.4, "food and drink: coffee", "in store")),
    (False, "Chipotle", charges("CHIPOTLE 1234", [13.85, 12.40, 13.85], 14, "food and drink: fast food", "in store")),
    (False, "Uber", charges("UBER *TRIP", [23.10, 14.80, 31.25], 9, "transportation: taxis and ride shares")),
    (False, "Delta", monthly("DELTA AIR 0062", 412.60, 1, "travel: flights")),
    (False, "Best Buy", monthly("BEST BUY 00321", 529.99, 1, "general merchandise: electronics", "in store")),
    (False, "Venmo", monthly("VENMO PAYMENT JANE", 40.00, 2, "transfer out: account transfer")),
    # Transfers: whether they "repeat" as a bill is beside the point; their line is scored.
    (None, "Ally savings", charges("TRANSFER TO ALLY SAVINGS XXXX1234", [300.00] * 4, 15, "transfer out: savings")),
    (None, "Vanguard", monthly("VANGUARD BUY INVESTMENT", 500.00, 2, "transfer out: investment and retirement funds")),
    (None, "Robinhood", monthly("ROBINHOOD DEBITS", 200.00, 2, "transfer out: investment and retirement funds")),
    (None, "Fidelity Roth IRA", monthly("FIDELITY ROTH IRA CONTRIB", 583.00, 2, "transfer out: investment and retirement funds")),
    (None, "Joint checking", charges("ONLINE TRANSFER TO CHK ...5521", [900.00] * 4, 15, "transfer out: account transfer")),
    # Genuinely ambiguous: reported, not scored.
    (None, "Sterling Group", monthly("STERLING GROUP WEB PMTS", 1554.91, 1, "home improvement: repair and maintenance")),
    (None, "Sterling Group", monthly("STERLING GROUP WEB PMTS", 1554.91, 1, "rent and utilities: rent")),
]


# (expected, payer, deposits)
PAY_CASES = [
    (True, "ACME Corp", charges("ACME CORP PAYROLL", 2400.00, 14, "income: wages")),
    (True, "ACME Corp", charges("ACME CORP PAYROLL", [2400.00] * 4, 14, "income: wages")),
    (True, "Gusto", charges("GUSTO PAY 123456", [3100.00, 3100.00], 15.2, "income: wages")),
    (False, "IRS", charges("IRS TREAS 310 TAX REF", 842.00, 1, "income: tax refund")),
    (False, "Chase", charges("INTEREST PAYMENT", [1.12, 1.08], 30.4, "income: interest earned")),
    (False, "Venmo", charges("VENMO CASHOUT", [45.00, 120.00], 12, "income: other income")),
]


# Asked alongside repeats, as PayeeJudge does. Not scored: printed so a batched run can be
# compared with a one-payee-per-request run.
FREQUENCY_QUESTION = {
    "type": "choice",
    "instructions": "If payments like these repeat, how often are they usually made?",
    "criteria": {
        "weekly": "Every week",
        "biweekly": "Every two weeks",
        "semi_monthly": "Twice a month, such as on the 1st and the 15th",
        "monthly": "Once a month",
        "quarterly": "Every three months",
        "semi_annually": "Twice a year",
        "annually": "Once a year",
    },
}


def payee_questions(payee, payee_charges, pay):
    if pay:
        return ({"payee": payee, "deposits": payee_charges},
                {"repeats": PAY_QUESTION, "usual_frequency": FREQUENCY_QUESTION})
    return ({"payee": payee, "charges": payee_charges},
            {"repeats": QUESTION, "usual_frequency": FREQUENCY_QUESTION, "line": LINE_QUESTION})


def post(key, state, questions):
    body = json.dumps({"model": "jev-latest", "state": state, "questions": questions}).encode()
    request = urllib.request.Request(
        "https://api.typesafe.ai/v1/systemone",
        data=body,
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)["answers"]


def ask(key, payee, payee_charges, pay=False):
    state, questions = payee_questions(payee, payee_charges, pay)
    answers = post(key, state, questions)
    return (answers["repeats"]["noul"], answers.get("line", {}).get("choice"),
            answers["usual_frequency"]["choice"])


def contained(state, question):
    """The question with the payee's state ahead of its own instructions, as PayeeJudge sends it."""
    own = question["instructions"]
    own = own if isinstance(own, dict) else {"question": own}
    return {**question, "instructions": {**state, **own}}


def ask_batch(key, cases, pay=False):
    """One request for several payees: each question carries its payee and charges."""
    questions = {}
    for i, (_, payee, payee_charges) in enumerate(cases):
        state, asked = payee_questions(payee, payee_charges, pay)
        for name, asking in asked.items():
            questions[f"{i}.{name}"] = contained(state, asking)
    answers = post(key, {}, questions)
    return [(answers[f"{i}.repeats"]["noul"], answers.get(f"{i}.line", {}).get("choice"),
             answers[f"{i}.usual_frequency"]["choice"])
            for i in range(len(cases))]


def ask_all(key, cases, batch, pay=False):
    if batch <= 1:
        with ThreadPoolExecutor(8) as pool:
            return list(pool.map(lambda case: ask(key, case[1], case[2], pay), cases))
    chunks = [cases[i:i + batch] for i in range(0, len(cases), batch)]
    with ThreadPoolExecutor(8) as pool:
        return [answer for chunk in pool.map(lambda chunk: ask_batch(key, chunk, pay), chunks) for answer in chunk]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--batch", type=int, default=1, help="payees per request")
    args = parser.parse_args()
    key = api_key()
    scores = ask_all(key, CASES, args.batch)

    scored = right = lines_scored = lines_right = 0
    for (expected, payee, payee_charges), (score, line, frequency) in zip(CASES, scores):
        verdict = " "
        if expected is not None:
            scored += 1
            ok = (score >= THRESHOLD) == expected
            right += ok
            verdict = " " if ok else "!"
        line_note = ""
        wanted = LINES.get(payee) if expected is not False else SPENDING_LINES.get(payee)
        if wanted:
            lines_scored += 1
            lines_right += line == wanted
            line_note = f"  -> {line}" + ("" if line == wanted else f"  (!) expected {wanted}")
        label = {True: "bill", False: "not", None: "?"}[expected]
        print(f"{verdict} {score:4.2f}  {label:4}  {frequency:13}  {payee} ×{len(payee_charges)} "
              f"[{payee_charges[0]['plaid_category']}]{line_note}")
    pay_scores = ask_all(key, PAY_CASES, args.batch, pay=True)
    pay_right = 0
    print()
    for (expected, payer, deposits), (score, _, frequency) in zip(PAY_CASES, pay_scores):
        ok = (score >= THRESHOLD) == expected
        pay_right += ok
        print(f"{' ' if ok else '!'} {score:4.2f}  {'pay' if expected else 'not':4}  {frequency:13}  {payer} ×{len(deposits)} "
              f"[{deposits[0]['plaid_category']}]")

    print(f"\nrepeats: {right}/{scored} right at {THRESHOLD}")
    print(f"line:    {lines_right}/{lines_scored} right")
    print(f"pay:     {pay_right}/{len(PAY_CASES)} right at {THRESHOLD}")
    passed = right == scored and lines_right == lines_scored and pay_right == len(PAY_CASES)
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
