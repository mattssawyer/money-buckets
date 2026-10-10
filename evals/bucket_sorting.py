"""Eval: can bucket sorting batch many transactions into one Jev request without losing accuracy?

Sorting asks one request per transaction today (BucketClassifier). An earlier try at batching
put 25 transactions in the state and pointed each question at `transactions[i]`; it was cheaper
and faster but lost the plan lines' context (Netflix went to fixed costs, Hulu and Spotify to
guilt-free). This compares three shapes of the same question:

- single:   today's request, one transaction and the plan lines in the state.
- shared:   the earlier try, every transaction in the state, one question per `transactions[i]`.
- isolated: only the plan lines in the state; each question carries its own transaction in its
            instructions, so no question sees the other transactions.
- contained: an empty state; each question carries its own transaction and the plan lines, so
            each reads exactly what a single request shows, at the cost of repeating the lines.

Scored against labeled cases under the plan setup's default lines. With --sandbox, also sorts
the transactions in the local database every way and reports how often the batched shapes agree
with single, which is what users get today.

Run from the repo root; reads TYPESAFE_API_KEY from the environment or server/.env:

    python3 evals/bucket_sorting.py [--batch 25] [--sandbox]

Ambiguous cases (expected None) are printed but not scored.
"""
import argparse
import csv
import io
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor


def api_key():
    if os.environ.get("TYPESAFE_API_KEY"):
        return os.environ["TYPESAFE_API_KEY"]
    env = os.path.join(os.path.dirname(__file__), "..", "server", ".env")
    for line in open(env):
        if line.startswith("TYPESAFE_API_KEY="):
            return line.split("=", 1)[1].strip()
    sys.exit("TYPESAFE_API_KEY isn't set")


# The question as BucketClassifier asks it; keep the wording in step with it.
CRITERIA = {
    "fixed_costs": {
        "what": "Bills and necessities the user must pay to live: rent or mortgage, utilities, "
        "phone, internet, insurance, loan and car payments, groceries, commuting, "
        "basic clothing and committed subscriptions.",
    },
    "guilt_free": {
        "what": "Optional spending the user chooses to enjoy: eating out, coffee, bars, "
        "entertainment, travel, hobbies, gifts, donations and non-essential shopping.",
        "includes": "Cash withdrawals and payments sent to other people, since that money gets spent.",
    },
    "savings": {
        "what": "Money moved into a savings account and set aside for a future goal, such as "
        "an emergency fund, a vacation or a house down payment.",
        "not_for": "Purchases themselves, even ones a savings goal will pay for, like a hotel booking.",
    },
    "investments": {
        "what": "Money moved into an investment or retirement account, such as a brokerage "
        "account, 401(k) or IRA.",
    },
    "not_counted": {
        "what": "Money moving between the user's own everyday accounts, such as checking to "
        "checking, without being spent, saved or invested.",
        "not_for": "Cash withdrawals or payments to other people.",
    },
}

QUESTION = "Which part of the user's Conscious Spending Plan does `{0}` belong to?"
GUIDANCE = "When `{0}` matches a line in `spending_plan`, choose the part that line is in."

# The spreadsheet's default lines, as plan setup starts with them (PlanLines.DEFAULT).
SPENDING_PLAN = {
    "fixed_costs": {"lines": ["Rent/mortgage", "Utilities", "Insurance", "Car payment", "Debt payments",
                              "Groceries", "Clothes", "Phone", "Internet", "Subscriptions"]},
    "investments": {"lines": ["401(k)", "Roth IRA", "Other investments"]},
    "savings": {"lines": ["Emergency fund", "Vacations", "Gifts", "House down payment"]},
}


def question(path, extra=None):
    instructions = {"question": QUESTION.format(path), "guidance": GUIDANCE.format(path)}
    if extra:
        instructions = {**extra, **instructions}
    return {"type": "choice", "instructions": instructions, "criteria": CRITERIA}


def transaction(description, merchant, amount, category, channel="online"):
    """As BucketClassifier.TransactionState: an unsigned amount and the direction it moved."""
    return {
        "description": description,
        "merchant": merchant,
        "amount_usd": round(abs(amount), 2),
        "direction": "money out" if amount >= 0 else "refund",
        "plaid_category": category,
        "payment_channel": channel,
    }


T = transaction

# (expected, transaction) under the default plan lines.
CASES = [
    # Subscriptions: the Subscriptions line puts them all in fixed costs, alike.
    ("fixed_costs", T("NETFLIX.COM", "Netflix", 15.49, "entertainment: tv and movies")),
    ("fixed_costs", T("HULU 877-8244858", "Hulu", 17.99, "entertainment: tv and movies")),
    ("fixed_costs", T("Spotify USA", "Spotify", 11.99, "entertainment: music and audio")),
    ("fixed_costs", T("DISNEY PLUS", "Disney+", 13.99, "entertainment: tv and movies")),
    ("fixed_costs", T("GOOGLE *YouTubePremium", "YouTube Premium", 13.99, "entertainment: tv and movies")),
    ("fixed_costs", T("OPENAI *CHATGPT SUBSCR", "OpenAI", 20.00, "general merchandise: software")),
    ("fixed_costs", T("ADOBE *CREATIVE CLD", "Adobe", 59.99, "general merchandise: software")),
    ("fixed_costs", T("NYTIMES*NYTIMES DIGITAL", "New York Times", 4.00, "entertainment: other entertainment")),
    # Bills and necessities.
    ("fixed_costs", T("AVAIL RENT PAYMENT", "Avail", 1850.00, "rent and utilities: rent")),
    ("fixed_costs", T("COMCAST XFINITY", "Xfinity", 79.99, "rent and utilities: internet and cable")),
    ("fixed_costs", T("T-MOBILE AUTOPAY", "T-Mobile", 70.00, "rent and utilities: telephone")),
    ("fixed_costs", T("DUKE ENERGY PAYMENT", "Duke Energy", 104.37, "rent and utilities: gas and electricity")),
    ("fixed_costs", T("GEICO AUTO PAY", "GEICO", 134.20, "general services: insurance")),
    ("fixed_costs", T("CHASE AUTO LOAN PMT", "Chase", 412.00, "loan payments: car payment")),
    ("fixed_costs", T("WHOLEFDS MKT", "Whole Foods", 97.54, "food and drink: groceries", "in store")),
    ("fixed_costs", T("TRADER JOE S #552", "Trader Joe's", 54.12, "food and drink: groceries", "in store")),
    ("fixed_costs", T("KROGER #412", "Kroger", 132.48, "food and drink: groceries", "in store")),
    ("fixed_costs", T("UNIQLO USA", "Uniqlo", 59.80, "general merchandise: clothing and accessories", "in store")),
    ("fixed_costs", T("OLDNAVY.COM", "Old Navy", 84.50, "general merchandise: clothing and accessories")),
    ("fixed_costs", T("MTA*NYCT PAYGO", "MTA", 2.90, "transportation: public transit", "in store")),
    # Spending the user chooses.
    ("guilt_free", T("STARBUCKS STORE 123", "Starbucks", 6.45, "food and drink: coffee", "in store")),
    ("guilt_free", T("CHIPOTLE 1234", "Chipotle", 13.85, "food and drink: fast food", "in store")),
    ("guilt_free", T("DOORDASH*THAI BASIL", "DoorDash", 38.20, "food and drink: restaurant")),
    ("guilt_free", T("THE RUSTY NAIL BAR", "The Rusty Nail", 46.00, "food and drink: beer wine and liquor", "in store")),
    ("guilt_free", T("DELTA AIR 0062", "Delta", 412.60, "travel: flights")),
    ("guilt_free", T("MARRIOTT HOTELS", "Marriott", 289.00, "travel: lodging")),
    ("guilt_free", T("AMC THEATRES 0451", "AMC", 31.50, "entertainment: tv and movies", "in store")),
    ("guilt_free", T("TICKETMASTER", "Ticketmaster", 145.00, "entertainment: sporting events amusement parks and museums")),
    ("guilt_free", T("STEAMGAMES.COM", "Steam", 29.99, "entertainment: video games")),
    ("guilt_free", T("BEST BUY 00321", "Best Buy", 529.99, "general merchandise: electronics", "in store")),
    ("guilt_free", T("AMERICAN RED CROSS", "American Red Cross", 50.00, "government and non profit: donations")),
    ("guilt_free", T("ATM WITHDRAWAL 1234 MAIN ST", None, 100.00, "transfer out: withdrawal", "in store")),
    ("guilt_free", T("VENMO PAYMENT JANE DOE", "Venmo", 40.00, "transfer out: account transfer", "other")),
    # A gift is a purchase, even with a Gifts savings line.
    ("guilt_free", T("ETSY.COM GIFT ORDER", "Etsy", 64.00, "general merchandise: gifts and novelties")),
    # Money set aside.
    ("savings", T("TRANSFER TO ALLY SAVINGS XXXX1234", None, 300.00, "transfer out: savings", "other")),
    ("savings", T("MARCUS HYSA DEPOSIT", "Marcus", 500.00, "transfer out: savings", "other")),
    ("investments", T("VANGUARD BUY INVESTMENT", "Vanguard", 500.00, "transfer out: investment and retirement funds", "other")),
    ("investments", T("FIDELITY ROTH IRA CONTRIB", "Fidelity", 583.00, "transfer out: investment and retirement funds", "other")),
    ("investments", T("ROBINHOOD DEBITS", "Robinhood", 200.00, "transfer out: investment and retirement funds", "other")),
    ("not_counted", T("ONLINE TRANSFER TO CHK ...5521", None, 900.00, "transfer out: account transfer", "other")),
    # Genuinely ambiguous: reported, not scored.
    (None, T("BP GAS STATION", "BP", 40.00, "transportation: gas", "in store")),
    (None, T("Planet Fitness", "Planet Fitness", 24.99, "personal care: gyms and fitness centers", "in store")),
    (None, T("UNIQLO USA", "Uniqlo", -39.90, "general merchandise: clothing and accessories", "in store")),
    (None, T("AMAZON MKTPL*2K4", "Amazon", 43.17, "general merchandise: online marketplaces")),
    (None, T("UBER *TRIP", "Uber", 23.10, "transportation: taxis and ride shares")),
]

# Never asked (BucketSorting.NOT_PLAN_MONEY), matched against primary and detailed categories.
NOT_PLAN_MONEY = {"INCOME", "TRANSFER_IN", "LOAN_PAYMENTS_CREDIT_CARD_PAYMENT"}


def sandbox_transactions():
    """The local database's transactions that sorting asks about, as BucketClassifier shows them."""
    query = ("COPY (SELECT name, merchant_name, amount, personal_finance_category_primary, "
             "personal_finance_category_detailed, payment_channel FROM transactions "
             "ORDER BY transaction_date DESC, transaction_id) TO STDOUT WITH CSV")
    out = subprocess.run(
        ["docker", "exec", "money_buckets-postgres-1", "psql", "-U", "abacus_app", "-d", "abacus", "-c", query],
        capture_output=True, text=True, check=True).stdout
    shown = []
    for name, merchant, amount, primary, detailed, channel in csv.reader(io.StringIO(out)):
        if primary in NOT_PLAN_MONEY or detailed in NOT_PLAN_MONEY:
            continue
        shown.append(T(name, merchant or None, float(amount), readable(primary or None, detailed or None),
                       channel or None))
    return shown


def readable(primary, detailed):
    """As PlaidCategory.readable."""
    words = lambda category: category.replace("_", " ").lower()
    if detailed is None:
        return None if primary is None else words(primary)
    if primary is None or not detailed.startswith(primary + "_"):
        return words(detailed)
    return words(primary) + ": " + words(detailed[len(primary) + 1:])


def post(key, state, questions):
    body = json.dumps({"model": "jev-latest", "state": state, "questions": questions}).encode()
    for attempt in range(5):
        request = urllib.request.Request(
            "https://api.typesafe.ai/v1/systemone",
            data=body,
            headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                return json.load(response)
        except urllib.error.HTTPError as e:
            if e.code not in (429, 529) or attempt == 4:
                raise RuntimeError(f"{e.code}: {e.read().decode()[:500]}") from e
        except (urllib.error.URLError, OSError):
            if attempt == 4:
                raise
        time.sleep(2 ** attempt)


def single(key, transactions, batch):
    """One request per transaction, as sorting asks today."""
    def ask(one):
        response = post(key, {"transaction": one, "spending_plan": SPENDING_PLAN},
                        {"bucket": question("transaction")})
        return response["answers"]["bucket"]["choice"], response["usage"]["input_tokens"]
    return run(transactions, ask, chunk=1)


def shared(key, transactions, batch):
    """Every transaction in the state; each question points at one of them."""
    def ask(chunk):
        state = {"transactions": chunk, "spending_plan": SPENDING_PLAN}
        questions = {f"t{i}": question(f"transactions[{i}]") for i in range(len(chunk))}
        response = post(key, state, questions)
        return [response["answers"][f"t{i}"]["choice"] for i in range(len(chunk))], response["usage"]["input_tokens"]
    return run(transactions, ask, chunk=batch)


def isolated(key, transactions, batch):
    """Only the plan lines in the state; each question carries its own transaction."""
    def ask(chunk):
        questions = {f"t{i}": question("transaction", {"transaction": one}) for i, one in enumerate(chunk)}
        response = post(key, {"spending_plan": SPENDING_PLAN}, questions)
        return [response["answers"][f"t{i}"]["choice"] for i in range(len(chunk))], response["usage"]["input_tokens"]
    return run(transactions, ask, chunk=batch)


def run(transactions, ask, chunk):
    """Asks in parallel, 8 at a time like sorting. Returns the buckets, requests, tokens and seconds."""
    chunks = [transactions[i:i + chunk] for i in range(0, len(transactions), chunk)]
    started = time.perf_counter()
    with ThreadPoolExecutor(8) as pool:
        results = list(pool.map(ask if chunk > 1 else lambda c: ask(c[0]), chunks))
    seconds = time.perf_counter() - started
    buckets = []
    for answer, _ in results:
        buckets.extend(answer if chunk > 1 else [answer])
    return buckets, len(chunks), sum(tokens for _, tokens in results), seconds


def contained(key, transactions, batch):
    """An empty state; each question carries its own transaction and the plan lines."""
    def ask(chunk):
        questions = {f"t{i}": question("transaction", {"transaction": one, "spending_plan": SPENDING_PLAN})
                     for i, one in enumerate(chunk)}
        response = post(key, {}, questions)
        return [response["answers"][f"t{i}"]["choice"] for i in range(len(chunk))], response["usage"]["input_tokens"]
    return run(transactions, ask, chunk=batch)


SHAPES = {"single": single, "shared": shared, "isolated": isolated, "contained": contained}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--batch", type=int, default=25, help="transactions per batched request")
    parser.add_argument("--sandbox", action="store_true", help="also sort the local database's transactions")
    args = parser.parse_args()
    key = api_key()

    shown = [one for _, one in CASES]
    results = {name: shape(key, shown, args.batch) for name, shape in SHAPES.items()}
    print(f"{'':2}{'expected':12}" + "".join(f"{name:13}" for name in SHAPES) + "transaction")
    right = dict.fromkeys(SHAPES, 0)
    for i, (expected, one) in enumerate(CASES):
        answers = {name: results[name][0][i] for name in SHAPES}
        cells = ""
        for name, answer in answers.items():
            ok = expected is None or answer == expected
            right[name] += expected is not None and ok
            cells += f"{answer + ('' if ok else ' !'):13}"
        print(f"{'  ' if len(set(answers.values())) == 1 else '≠ '}{expected or '?':12}{cells}"
              f"{one['merchant'] or one['description']} ${one['amount_usd']} {one['direction']} [{one['plaid_category']}]")

    scored = sum(expected is not None for expected, _ in CASES)
    print(f"\nlabeled cases, batches of {args.batch}:")
    for name in SHAPES:
        _, requests, tokens, seconds = results[name]
        print(f"  {name:9} {right[name]:3}/{scored} right   {requests:3} requests  {tokens:7,} input tokens  {seconds:5.1f}s")

    if args.sandbox:
        sandbox = sandbox_transactions()
        results = {name: shape(key, sandbox, args.batch) for name, shape in SHAPES.items()}
        baseline = results["single"][0]
        print(f"\nsandbox: {len(sandbox)} transactions, batches of {args.batch}; agreement with single:")
        for name in SHAPES:
            buckets, requests, tokens, seconds = results[name]
            agree = sum(a == b for a, b in zip(buckets, baseline))
            print(f"  {name:9} {agree:3}/{len(sandbox)} agree   {requests:3} requests  {tokens:7,} input tokens  {seconds:5.1f}s")
        for i, one in enumerate(sandbox):
            answers = [results[name][0][i] for name in SHAPES]
            if len(set(answers)) > 1:
                print("  ≠ " + "".join(f"{a:13}" for a in answers)
                      + f"{one['merchant'] or one['description']} ${one['amount_usd']} [{one['plaid_category']}]")

    return 0 if all(right[name] == scored for name in ("single", "contained")) else 1


if __name__ == "__main__":
    sys.exit(main())
