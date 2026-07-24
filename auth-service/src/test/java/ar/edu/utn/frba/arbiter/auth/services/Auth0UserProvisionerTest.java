package ar.edu.utn.frba.arbiter.auth.services;

import ar.edu.utn.frba.arbiter.auth.config.AuthProperties;
import ar.edu.utn.frba.arbiter.auth.exceptions.Auth0ProvisioningException;
import com.auth0.client.auth.AuthAPI;
import com.auth0.client.mgmt.ManagementAPI;
import com.auth0.client.mgmt.UsersEntity;
import com.auth0.exception.Auth0Exception;
import com.auth0.json.auth.TokenHolder;
import com.auth0.json.mgmt.users.User;
import com.auth0.net.Request;
import com.auth0.net.Response;
import com.auth0.net.TokenRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Auth0UserProvisionerTest {

    private static final String DOMAIN = "example.auth0.com";
    private static final String CONNECTION = "Username-Password-Authentication";
    private static final String EMAIL = "nuevo.analista@arbiter.test";
    private static final String PASSWORD = "changeme123";
    private static final String ACCESS_TOKEN = "m2m-access-token";

    @Mock
    private AuthAPI auth0ManagementAuthApi;

    @Mock
    private TokenRequest tokenRequest;

    @Mock
    private Response<TokenHolder> tokenResponse;

    @Mock
    private TokenHolder tokenHolder;

    private Auth0UserProvisioner provisioner() {
        AuthProperties properties = new AuthProperties(
                "auth0",
                new AuthProperties.Jwt("test-secret", 60),
                new AuthProperties.Login(5, 15),
                new AuthProperties.Auth0(DOMAIN, "client-id", "client-secret", CONNECTION),
                new AuthProperties.ManagementApi("mgmt-client-id", "mgmt-client-secret"));
        return new Auth0UserProvisioner(auth0ManagementAuthApi, properties);
    }

    private void stubM2mToken() throws Auth0Exception {
        when(auth0ManagementAuthApi.requestToken("https://" + DOMAIN + "/api/v2/")).thenReturn(tokenRequest);
        when(tokenRequest.execute()).thenReturn(tokenResponse);
        when(tokenResponse.getBody()).thenReturn(tokenHolder);
        when(tokenHolder.getAccessToken()).thenReturn(ACCESS_TOKEN);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createUser_auth0AcceptsUser_createsItInManagementApi() throws Auth0Exception {
        stubM2mToken();

        UsersEntity usersEntity = org.mockito.Mockito.mock(UsersEntity.class);
        Request<User> createRequest = org.mockito.Mockito.mock(Request.class);
        when(usersEntity.create(any(User.class))).thenReturn(createRequest);
        when(createRequest.execute()).thenReturn(org.mockito.Mockito.mock(Response.class));

        try (MockedConstruction<ManagementAPI> mocked = mockConstruction(ManagementAPI.class,
                (mock, context) -> {
                    assertThatArgsMatchDomainAndToken(context.arguments());
                    when(mock.users()).thenReturn(usersEntity);
                })) {
            provisioner().createUser(EMAIL, PASSWORD);

            org.mockito.ArgumentCaptor<User> captor = org.mockito.ArgumentCaptor.forClass(User.class);
            verify(usersEntity).create(captor.capture());
            org.assertj.core.api.Assertions.assertThat(captor.getValue().getEmail()).isEqualTo(EMAIL);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void createUser_managementApiRejects_throwsAuth0ProvisioningException() throws Auth0Exception {
        stubM2mToken();

        UsersEntity usersEntity = org.mockito.Mockito.mock(UsersEntity.class);
        Request<User> createRequest = org.mockito.Mockito.mock(Request.class);
        when(usersEntity.create(any(User.class))).thenReturn(createRequest);
        when(createRequest.execute()).thenThrow(new Auth0Exception("email already exists"));

        try (MockedConstruction<ManagementAPI> mocked = mockConstruction(ManagementAPI.class,
                (mock, context) -> when(mock.users()).thenReturn(usersEntity))) {
            assertThatThrownBy(() -> provisioner().createUser(EMAIL, PASSWORD))
                    .isInstanceOf(Auth0ProvisioningException.class);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteUser_userExistsInAuth0_deletesItByAuth0Id() throws Auth0Exception {
        stubM2mToken();

        String auth0UserId = "auth0|6a5fcb701c7dd907a68b98a4";
        User existingUser = org.mockito.Mockito.mock(User.class);
        when(existingUser.getId()).thenReturn(auth0UserId);

        UsersEntity usersEntity = org.mockito.Mockito.mock(UsersEntity.class);
        Request<java.util.List<User>> listRequest = org.mockito.Mockito.mock(Request.class);
        Response<java.util.List<User>> listResponse = org.mockito.Mockito.mock(Response.class);
        when(usersEntity.listByEmail(eq(EMAIL), any())).thenReturn(listRequest);
        when(listRequest.execute()).thenReturn(listResponse);
        when(listResponse.getBody()).thenReturn(java.util.List.of(existingUser));

        Request<Void> deleteRequest = org.mockito.Mockito.mock(Request.class);
        when(usersEntity.delete(auth0UserId)).thenReturn(deleteRequest);
        when(deleteRequest.execute()).thenReturn(org.mockito.Mockito.mock(Response.class));

        try (MockedConstruction<ManagementAPI> mocked = mockConstruction(ManagementAPI.class,
                (mock, context) -> when(mock.users()).thenReturn(usersEntity))) {
            provisioner().deleteUser(EMAIL);

            verify(usersEntity).delete(auth0UserId);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteUser_userNotFoundInAuth0_doesNothing() throws Auth0Exception {
        stubM2mToken();

        UsersEntity usersEntity = org.mockito.Mockito.mock(UsersEntity.class);
        Request<java.util.List<User>> listRequest = org.mockito.Mockito.mock(Request.class);
        Response<java.util.List<User>> listResponse = org.mockito.Mockito.mock(Response.class);
        when(usersEntity.listByEmail(eq(EMAIL), any())).thenReturn(listRequest);
        when(listRequest.execute()).thenReturn(listResponse);
        when(listResponse.getBody()).thenReturn(java.util.List.of());

        try (MockedConstruction<ManagementAPI> mocked = mockConstruction(ManagementAPI.class,
                (mock, context) -> when(mock.users()).thenReturn(usersEntity))) {
            provisioner().deleteUser(EMAIL);

            verify(usersEntity, org.mockito.Mockito.never()).delete(any());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void deleteUser_managementApiRejects_throwsAuth0ProvisioningException() throws Auth0Exception {
        stubM2mToken();

        UsersEntity usersEntity = org.mockito.Mockito.mock(UsersEntity.class);
        Request<java.util.List<User>> listRequest = org.mockito.Mockito.mock(Request.class);
        when(usersEntity.listByEmail(eq(EMAIL), any())).thenReturn(listRequest);
        when(listRequest.execute()).thenThrow(new Auth0Exception("management API down"));

        try (MockedConstruction<ManagementAPI> mocked = mockConstruction(ManagementAPI.class,
                (mock, context) -> when(mock.users()).thenReturn(usersEntity))) {
            assertThatThrownBy(() -> provisioner().deleteUser(EMAIL))
                    .isInstanceOf(Auth0ProvisioningException.class);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void updatePassword_userExistsInAuth0_updatesItByAuth0Id() throws Auth0Exception {
        stubM2mToken();

        String auth0UserId = "auth0|6a5fcb701c7dd907a68b98a4";
        User existingUser = org.mockito.Mockito.mock(User.class);
        when(existingUser.getId()).thenReturn(auth0UserId);

        UsersEntity usersEntity = org.mockito.Mockito.mock(UsersEntity.class);
        Request<java.util.List<User>> listRequest = org.mockito.Mockito.mock(Request.class);
        Response<java.util.List<User>> listResponse = org.mockito.Mockito.mock(Response.class);
        when(usersEntity.listByEmail(eq(EMAIL), any())).thenReturn(listRequest);
        when(listRequest.execute()).thenReturn(listResponse);
        when(listResponse.getBody()).thenReturn(java.util.List.of(existingUser));

        Request<User> updateRequest = org.mockito.Mockito.mock(Request.class);
        when(usersEntity.update(eq(auth0UserId), any(User.class))).thenReturn(updateRequest);
        when(updateRequest.execute()).thenReturn(org.mockito.Mockito.mock(Response.class));

        try (MockedConstruction<ManagementAPI> mocked = mockConstruction(ManagementAPI.class,
                (mock, context) -> when(mock.users()).thenReturn(usersEntity))) {
            provisioner().updatePassword(EMAIL, "OtraPass456!");

            verify(usersEntity).update(eq(auth0UserId), any(User.class));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void updatePassword_userNotFoundInAuth0_throwsAuth0ProvisioningException() throws Auth0Exception {
        stubM2mToken();

        UsersEntity usersEntity = org.mockito.Mockito.mock(UsersEntity.class);
        Request<java.util.List<User>> listRequest = org.mockito.Mockito.mock(Request.class);
        Response<java.util.List<User>> listResponse = org.mockito.Mockito.mock(Response.class);
        when(usersEntity.listByEmail(eq(EMAIL), any())).thenReturn(listRequest);
        when(listRequest.execute()).thenReturn(listResponse);
        when(listResponse.getBody()).thenReturn(java.util.List.of());

        try (MockedConstruction<ManagementAPI> mocked = mockConstruction(ManagementAPI.class,
                (mock, context) -> when(mock.users()).thenReturn(usersEntity))) {
            assertThatThrownBy(() -> provisioner().updatePassword(EMAIL, "OtraPass456!"))
                    .isInstanceOf(Auth0ProvisioningException.class);

            verify(usersEntity, org.mockito.Mockito.never()).update(any(), any());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void updatePassword_managementApiRejects_throwsAuth0ProvisioningException() throws Auth0Exception {
        stubM2mToken();

        String auth0UserId = "auth0|6a5fcb701c7dd907a68b98a4";
        User existingUser = org.mockito.Mockito.mock(User.class);
        when(existingUser.getId()).thenReturn(auth0UserId);

        UsersEntity usersEntity = org.mockito.Mockito.mock(UsersEntity.class);
        Request<java.util.List<User>> listRequest = org.mockito.Mockito.mock(Request.class);
        Response<java.util.List<User>> listResponse = org.mockito.Mockito.mock(Response.class);
        when(usersEntity.listByEmail(eq(EMAIL), any())).thenReturn(listRequest);
        when(listRequest.execute()).thenReturn(listResponse);
        when(listResponse.getBody()).thenReturn(java.util.List.of(existingUser));

        Request<User> updateRequest = org.mockito.Mockito.mock(Request.class);
        when(usersEntity.update(eq(auth0UserId), any(User.class))).thenReturn(updateRequest);
        when(updateRequest.execute()).thenThrow(new Auth0Exception("weak password"));

        try (MockedConstruction<ManagementAPI> mocked = mockConstruction(ManagementAPI.class,
                (mock, context) -> when(mock.users()).thenReturn(usersEntity))) {
            assertThatThrownBy(() -> provisioner().updatePassword(EMAIL, "OtraPass456!"))
                    .isInstanceOf(Auth0ProvisioningException.class);
        }
    }

    private void assertThatArgsMatchDomainAndToken(java.util.List<?> arguments) {
        org.assertj.core.api.Assertions.assertThat(arguments.get(0)).isEqualTo(DOMAIN);
        org.assertj.core.api.Assertions.assertThat(arguments.get(1)).isEqualTo(ACCESS_TOKEN);
    }
}
