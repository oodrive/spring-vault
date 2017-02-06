/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.vault.authentication;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.vault.client.VaultClient;
import org.springframework.vault.client.VaultResponseEntity;
import org.springframework.vault.support.VaultResponse;
import org.springframework.vault.support.VaultToken;

import java.net.URI;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LifecycleAwareSessionManager}.
 *
 * @author Mark Paluch
 */
@RunWith(MockitoJUnitRunner.class)
public class LifecycleAwareSessionManagerUnitTests {

	@Mock
	private ClientAuthentication clientAuthentication;

	@Mock
	private AsyncTaskExecutor taskExecutor;

	@Mock
	private ThreadPoolTaskScheduler taskScheduler;

	@Mock
	private VaultClient vaultClient;

	private LifecycleAwareSessionManager sessionManager;

	@Before
	public void before() throws Exception {
		sessionManager = new LifecycleAwareSessionManager(clientAuthentication,
				taskExecutor, vaultClient);
	}

	@Test
	public void shouldObtainTokenFromClientAuthentication() {

		when(clientAuthentication.login()).thenReturn(LoginToken.of("login"));

		assertThat(sessionManager.getSessionToken()).isEqualTo(LoginToken.of("login"));
	}

	@Test
	public void shouldRevokeLoginTokenOnDestroy() {

		when(clientAuthentication.login()).thenReturn(LoginToken.of("login"));

		when(
				vaultClient.postForEntity(eq("auth/token/revoke-self"),
						eq(LoginToken.of("login")), ArgumentMatchers.any(),
						any(Class.class))).thenReturn(
				new ResponseEntity<Object>(null, HttpStatus.OK, null, null));

		sessionManager.renewToken();
		sessionManager.destroy();

		verify(vaultClient).postForEntity(eq("auth/token/revoke-self"),
				eq(LoginToken.of("login")), ArgumentMatchers.any(), any(Class.class));
	}

	@Test
	public void shouldNotRevokeRegularTokenOnDestroy() {

		when(clientAuthentication.login()).thenReturn(VaultToken.of("login"));

		sessionManager.renewToken();
		sessionManager.destroy();

		verifyZeroInteractions(vaultClient);
	}

	@Test
	public void shouldNotThrowExceptionsOnRevokeErrors() {

		when(clientAuthentication.login()).thenReturn(LoginToken.of("login"));

		when(
				vaultClient.postForEntity(eq("auth/token/revoke-self"),
						eq(LoginToken.of("login")), ArgumentMatchers.any(),
						any(Class.class))).thenReturn(
				new ResponseEntity<Object>(null, HttpStatus.INTERNAL_SERVER_ERROR, null,
						null));

		sessionManager.renewToken();
		sessionManager.destroy();

		verify(vaultClient).postForEntity(eq("auth/token/revoke-self"),
				eq(LoginToken.of("login")), ArgumentMatchers.any(), any(Class.class));
	}

	@Test
	public void shouldScheduleTokenRenewal() {

		when(clientAuthentication.login()).thenReturn(LoginToken.renewable("login", 5));
		when(
				vaultClient.getForEntity(eq("auth/token/lookup-self"),
						eq(LoginToken.renewable("login", 5)), any(Class.class))).thenReturn(
				new ResponseEntity<VaultResponse>(tokenLookupResponse(), HttpStatus.OK, null, null));

		sessionManager.getSessionToken();

		verify(taskExecutor).execute(any(Runnable.class));
	}

	@Test
	public void shouldRunTokenRenewal() {

		when(clientAuthentication.login()).thenReturn(LoginToken.renewable("login", 5));
		when(
				vaultClient.postForEntity(eq("auth/token/renew-self"),
						eq(LoginToken.renewable("login", 5)), ArgumentMatchers.any(),
						any(Class.class))).thenReturn(
				new ResponseEntity<Object>(null, HttpStatus.OK, null, null));
		when(
				vaultClient.getForEntity(eq("auth/token/lookup-self"),
						eq(LoginToken.renewable("login", 5)), any(Class.class))).thenReturn(
				new ResponseEntity<VaultResponse>(tokenLookupResponse(), HttpStatus.OK, null, null));

		ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);

		sessionManager.getSessionToken();
		verify(taskExecutor).execute(runnableCaptor.capture());

		runnableCaptor.getValue().run();
		verify(vaultClient).postForEntity(eq("auth/token/renew-self"),
				eq(LoginToken.renewable("login", 5)), ArgumentMatchers.any(),
				any(Class.class));
		verify(clientAuthentication, times(1)).login();
	}

	@Test
	public void shouldReScheduleTokenRenewalAfterSucessfulRenewal() {

		when(clientAuthentication.login()).thenReturn(LoginToken.renewable("login", 5));
		when(
				vaultClient.postForEntity(eq("auth/token/renew-self"),
						eq(LoginToken.renewable("login", 5)), ArgumentMatchers.any(),
						any(Class.class))).thenReturn(
				new ResponseEntity<Object>(null, HttpStatus.OK, null, null));
		when(
				vaultClient.getForEntity(eq("auth/token/lookup-self"),
						eq(LoginToken.renewable("login", 5)), any(Class.class))).thenReturn(
				new ResponseEntity<VaultResponse>(tokenLookupResponse(), HttpStatus.OK, null, null));

		ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);

		sessionManager.getSessionToken();
		verify(taskExecutor).execute(runnableCaptor.capture());

		runnableCaptor.getValue().run();

		verify(taskExecutor, times(2)).execute(any(Runnable.class));
	}

	@Test
	public void shouldUseTaskScheduler() {

		sessionManager = new LifecycleAwareSessionManager(clientAuthentication,
				taskScheduler, vaultClient);

		when(clientAuthentication.login()).thenReturn(LoginToken.renewable("login", 5));
		when(
				vaultClient.getForEntity(eq("auth/token/lookup-self"),
						eq(LoginToken.renewable("login", 5)), any(Class.class))).thenReturn(
				new ResponseEntity<VaultResponse>(tokenLookupResponse(), HttpStatus.OK, null, null));

		ArgumentCaptor<Trigger> triggerCaptor = ArgumentCaptor.forClass(Trigger.class);

		sessionManager.getSessionToken();
		verify(taskScheduler).schedule(any(Runnable.class), triggerCaptor.capture());

		assertThat(triggerCaptor.getValue().nextExecutionTime(null)).isNotNull();
		assertThat(triggerCaptor.getValue().nextExecutionTime(null)).isNull();
	}

	@Test
	public void shouldNotReScheduleTokenRenewalAfterFailedRenewal() {

		when(clientAuthentication.login()).thenReturn(LoginToken.renewable("login", 5));
		when(
				vaultClient.postForEntity(eq("auth/token/renew-self"),
						eq(LoginToken.renewable("login", 5)), ArgumentMatchers.any(),
						any(Class.class))).thenReturn(
				new ResponseEntity<Object>(null, HttpStatus.INTERNAL_SERVER_ERROR, null,
						null));
		when(
				vaultClient.getForEntity(eq("auth/token/lookup-self"),
						eq(LoginToken.renewable("login", 5)), any(Class.class))).thenReturn(
				new ResponseEntity<VaultResponse>(tokenLookupResponse(), HttpStatus.OK, null, null));

		ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);

		sessionManager.getSessionToken();
		verify(taskExecutor).execute(runnableCaptor.capture());

		runnableCaptor.getValue().run();

		verify(taskExecutor, times(1)).execute(any(Runnable.class));
	}

	@Test
	public void shouldObtainTokenIfNoTokenAvailable() {

		when(clientAuthentication.login()).thenReturn(LoginToken.renewable("login", 5));
		when(
				vaultClient.getForEntity(eq("auth/token/lookup-self"),
						eq(LoginToken.renewable("login", 5)), any(Class.class))).thenReturn(
				new ResponseEntity<VaultResponse>(tokenLookupResponse(), HttpStatus.OK, null, null));

		sessionManager.renewToken();

		assertThat(sessionManager.getSessionToken()).isEqualTo(
				LoginToken.renewable("login", 5));
		verify(clientAuthentication, times(1)).login();
	}

	@Test
	public void renewShouldReportFalseIfTokenRenewalFails() {

		when(clientAuthentication.login()).thenReturn(LoginToken.renewable("login", 5));
		when(
				vaultClient.postForEntity(anyString(), any(VaultToken.class),
						ArgumentMatchers.any(), any(Class.class))).thenReturn(
				new ResponseEntity<Object>(null, HttpStatus.BAD_REQUEST, null, null));
		when(
				vaultClient.getForEntity(eq("auth/token/lookup-self"),
						eq(LoginToken.renewable("login", 5)), any(Class.class))).thenReturn(
				new ResponseEntity<VaultResponse>(tokenLookupResponse(), HttpStatus.OK, null, null));

		sessionManager.getSessionToken();

		assertThat(sessionManager.renewToken()).isFalse();
		verify(clientAuthentication, times(1)).login();
	}

	private VaultResponse tokenLookupResponse() {
		HashMap<String, Object> data = new HashMap<String, Object>();
		data.put("accessor", "token-accessor");
		data.put("creation_time", 0);
		data.put("creation_ttl", 3600);
		data.put("display_name", "token-name");
		data.put("explicit_max_ttl", 0);
		data.put("num_uses", 0);
		data.put("orphan", false);
		data.put("path", "auth/token/create");
		data.put("policies", new String[]{"default"});
		data.put("renewable", true);
		data.put("ttl", 10);

		VaultResponse vaultResponse = new VaultResponse();
		vaultResponse.setData(data);
		return vaultResponse;
	}

	static class ResponseEntity<T> extends VaultResponseEntity<T> {

		protected ResponseEntity(T body, HttpStatus statusCode, URI uri, String message) {
			super(body, statusCode, uri, message);
		}
	}
}