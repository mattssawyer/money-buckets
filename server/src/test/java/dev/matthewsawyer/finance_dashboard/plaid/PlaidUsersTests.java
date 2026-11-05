package dev.matthewsawyer.finance_dashboard.plaid;

import com.plaid.client.model.UserCreateRequest;
import com.plaid.client.model.UserCreateResponse;
import com.plaid.client.model.UserRemoveRequest;
import com.plaid.client.model.UserRemoveResponse;
import com.plaid.client.request.PlaidApi;
import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.repository.UserRepository;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import retrofit2.Call;
import retrofit2.Response;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlaidUsersTests {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private PlaidApi plaidApi;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Call<UserCreateResponse> createCall;

    @Mock
    private Call<UserRemoveResponse> removeCall;

    @Test
    void createsAPlaidUserTheFirstTimeAndStoresIt() throws IOException {
        User user = user(null);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(plaidApi.userCreate(any(UserCreateRequest.class), eq(true))).thenReturn(createCall);
        when(createCall.execute()).thenReturn(Response.success(new UserCreateResponse().userId("usr_new")));

        assertEquals("usr_new", plaidUsers().plaidUserId(USER_ID));

        ArgumentCaptor<UserCreateRequest> request = ArgumentCaptor.forClass(UserCreateRequest.class);
        verify(plaidApi).userCreate(request.capture(), eq(true));
        assertEquals(USER_ID.toString(), request.getValue().getClientUserId());
        assertEquals("usr_new", user.getPlaidUserId());
        verify(userRepository).save(user);
    }

    @Test
    void reusesTheStoredPlaidUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user("usr_saved")));

        assertEquals("usr_saved", plaidUsers().plaidUserId(USER_ID));
        verifyNoInteractions(plaidApi);
    }

    @Test
    void removesThePlaidUser() throws IOException {
        when(plaidApi.userRemove(any(UserRemoveRequest.class), eq(true))).thenReturn(removeCall);
        when(removeCall.execute()).thenReturn(Response.success(new UserRemoveResponse()));

        plaidUsers().remove(user("usr_saved"));

        ArgumentCaptor<UserRemoveRequest> request = ArgumentCaptor.forClass(UserRemoveRequest.class);
        verify(plaidApi).userRemove(request.capture(), eq(true));
        assertEquals("usr_saved", request.getValue().getUserId());
    }

    @Test
    void hasNothingToRemoveForAUserWhoNeverOpenedLink() {
        plaidUsers().remove(user(null));

        verifyNoInteractions(plaidApi);
    }

    @Test
    void treatsAPlaidUserPlaidNoLongerHasAsRemoved() throws IOException {
        when(plaidApi.userRemove(any(UserRemoveRequest.class), eq(true))).thenReturn(removeCall);
        when(removeCall.execute()).thenReturn(error("USER_NOT_FOUND"));

        assertDoesNotThrow(() -> plaidUsers().remove(user("usr_gone")));
    }

    @Test
    void stopsWhenPlaidCannotRemoveThePlaidUser() throws IOException {
        when(plaidApi.userRemove(any(UserRemoveRequest.class), eq(true))).thenReturn(removeCall);
        when(removeCall.execute()).thenReturn(error("INTERNAL_SERVER_ERROR"));

        assertThrows(PlaidRequestException.class, () -> plaidUsers().remove(user("usr_saved")));
    }

    private PlaidUsers plaidUsers() {
        return new PlaidUsers(plaidApi, userRepository);
    }

    private static User user(String plaidUserId) {
        User user = new User("auth-user");
        ReflectionTestUtils.setField(user, "id", USER_ID);
        user.setPlaidUserId(plaidUserId);
        return user;
    }

    private static <T> Response<T> error(String errorCode) {
        return Response.error(400, ResponseBody.create(
                "{\"error_code\":\"" + errorCode + "\"}", MediaType.get("application/json")));
    }
}
