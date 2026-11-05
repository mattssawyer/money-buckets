package dev.matthewsawyer.finance_dashboard.plaid;

import com.plaid.client.model.UserCreateRequest;
import com.plaid.client.model.UserRemoveRequest;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Each user's Plaid user, which Link needs to add several banks in one session. Plaid knows the
 * user only by our internal id.
 */
@Service
public class PlaidUsers {

    /** Asks /user/create for the newer user that has a user_id and no user_token. */
    private static final boolean NEW_USER_API = true;

    private final PlaidApi plaidApi;
    private final UserRepository userRepository;

    public PlaidUsers(PlaidApi plaidApi, UserRepository userRepository) {
        this.plaidApi = plaidApi;
        this.userRepository = userRepository;
    }

    /**
     * The user's Plaid user id, created and stored the first time. Plaid returns the same user
     * for the same internal id, so a create that raced another one or was lost is harmless.
     *
     * @throws PlaidRequestException when Plaid cannot create the user
     */
    public String plaidUserId(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("No user " + userId));
        if (user.getPlaidUserId() != null) {
            return user.getPlaidUserId();
        }
        String plaidUserId = PlaidCalls.execute(
                plaidApi.userCreate(new UserCreateRequest().clientUserId(userId.toString()), NEW_USER_API),
                "user create").getUserId();
        user.setPlaidUserId(plaidUserId);
        userRepository.save(user);
        return plaidUserId;
    }

    /**
     * Removes the user's Plaid user, if they have one. A user Plaid no longer has counts as
     * removed.
     *
     * @throws PlaidRequestException when Plaid cannot remove the user
     */
    public void remove(User user) {
        if (user.getPlaidUserId() == null) {
            return;
        }
        try {
            PlaidCalls.execute(
                    plaidApi.userRemove(new UserRemoveRequest().userId(user.getPlaidUserId()), NEW_USER_API),
                    "user remove");
        } catch (PlaidRequestException e) {
            if (!"USER_NOT_FOUND".equals(e.getErrorCode())) throw e;
        }
    }
}
