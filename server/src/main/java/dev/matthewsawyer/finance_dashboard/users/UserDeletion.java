package dev.matthewsawyer.finance_dashboard.users;

import dev.matthewsawyer.finance_dashboard.model.PlaidItem;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.plaid.PlaidItemLinking;
import dev.matthewsawyer.finance_dashboard.plaid.PlaidRequestException;
import dev.matthewsawyer.finance_dashboard.repository.PlaidItemRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * Deletes a user and everything stored about them: their Plaid items are removed from Plaid, every
 * row of theirs is deleted, and so is their Clerk sign-in. Nothing is kept for undoing it.
 */
@Service
public class UserDeletion {

    private static final Logger log = LoggerFactory.getLogger(UserDeletion.class);

    /**
     * Every table holding a user's rows, each before any table it references. A new table with a
     * user_id column fails UserDeletionTests until it's added here.
     */
    private static final List<String> DELETES = List.of(
            """
            DELETE FROM spending_plan_items WHERE line_id IN (
                SELECT l.id FROM spending_plan_lines l JOIN spending_plans p ON l.plan_id = p.id
                WHERE p.user_id = ?)""",
            "DELETE FROM spending_plan_lines WHERE plan_id IN (SELECT id FROM spending_plans WHERE user_id = ?)",
            "DELETE FROM spending_plans WHERE user_id = ?",
            "DELETE FROM transaction_counting WHERE user_id = ?",
            "DELETE FROM payee_corrections WHERE user_id = ?",
            "DELETE FROM recurring_answers WHERE user_id = ?",
            "DELETE FROM recurring_payees WHERE user_id = ?",
            "DELETE FROM balance_snapshots WHERE user_id = ?",
            "DELETE FROM account_drops WHERE user_id = ?",
            "DELETE FROM transactions WHERE user_id = ?",
            "DELETE FROM recurring_streams WHERE user_id = ?",
            "DELETE FROM accounts WHERE user_id = ?",
            "DELETE FROM plaid_items WHERE user_id = ?",
            "DELETE FROM users WHERE id = ?");

    private final PlaidItemRepository plaidItemRepository;
    private final PlaidItemLinking itemLinking;
    private final ClerkUsers clerkUsers;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;

    public UserDeletion(
            PlaidItemRepository plaidItemRepository,
            PlaidItemLinking itemLinking,
            ClerkUsers clerkUsers,
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            EntityManager entityManager
    ) {
        this.plaidItemRepository = plaidItemRepository;
        this.itemLinking = itemLinking;
        this.clerkUsers = clerkUsers;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.entityManager = entityManager;
    }

    /** Whether users can be deleted here, which needs Clerk's secret key. */
    public boolean isAvailable() {
        return clerkUsers.canDelete();
    }

    /**
     * Removes the user's items from Plaid first, so Plaid stops holding their bank access, then
     * deletes their rows, then their Clerk user. A failure stops it there and it can be run again:
     * items already removed stay removed, and a user whose rows are gone but whose sign-in
     * remains gets an empty user again on their next request.
     *
     * @throws IllegalStateException when users can't be deleted here (see {@link #isAvailable});
     *         nothing is touched
     * @throws PlaidRequestException when Plaid can't remove an item; items removed before it stay
     *         removed, and nothing is deleted
     * @throws ClerkUsers.ClerkRequestException when Clerk can't delete the sign-in; the rows are
     *         already gone
     */
    public void delete(User user) {
        if (!isAvailable()) {
            throw new IllegalStateException("CLERK_SECRET_KEY is not set");
        }
        for (PlaidItem item : plaidItemRepository.findAllByUserIdAndRemovedOnIsNullOrderByItemIdAsc(user.getId())) {
            itemLinking.remove(user.getId(), item.getItemId());
        }
        transactionTemplate.executeWithoutResult(status -> {
            // The deletes bypass Hibernate, so nothing it still holds may be written after them.
            entityManager.flush();
            DELETES.forEach(sql -> jdbcTemplate.update(sql, user.getId()));
            entityManager.clear();
        });
        log.info("Deleted user {} and their data", user.getId());
        clerkUsers.delete(user.getClerkUserId());
    }
}
