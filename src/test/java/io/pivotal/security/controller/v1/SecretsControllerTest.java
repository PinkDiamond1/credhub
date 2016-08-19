package io.pivotal.security.controller.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.greghaskins.spectrum.Spectrum;
import io.pivotal.security.CredentialManagerApp;
import io.pivotal.security.entity.NamedCertificateSecret;
import io.pivotal.security.entity.NamedSecret;
import io.pivotal.security.entity.NamedStringSecret;
import io.pivotal.security.generator.SecretGenerator;
import io.pivotal.security.repository.CertificateAuthorityRepository;
import io.pivotal.security.repository.SecretRepository;
import io.pivotal.security.view.CertificateSecret;
import io.pivotal.security.view.StringSecret;
import org.exparity.hamcrest.BeanMatchers;
import org.junit.Assert;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.SpringApplicationConfiguration;
import org.springframework.context.MessageSource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.provider.authentication.OAuth2AuthenticationDetails;
import org.springframework.security.oauth2.provider.token.ResourceServerTokenServices;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.ConfigurableWebApplicationContext;

import static com.greghaskins.spectrum.Spectrum.afterEach;
import static com.greghaskins.spectrum.Spectrum.beforeEach;
import static com.greghaskins.spectrum.Spectrum.describe;
import static com.greghaskins.spectrum.Spectrum.it;
import static io.pivotal.security.helper.SpectrumHelper.autoTransactional;
import static io.pivotal.security.helper.SpectrumHelper.mockOutCurrentTimeProvider;
import static io.pivotal.security.helper.SpectrumHelper.wireAndUnwire;
import static junit.framework.TestCase.assertNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.refEq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

@RunWith(Spectrum.class)
@SpringApplicationConfiguration(classes = CredentialManagerApp.class)
@WebAppConfiguration
@ActiveProfiles("unit-test")
public class SecretsControllerTest {

  @Autowired
  private MessageSource messageSource;

  @Autowired
  protected ConfigurableWebApplicationContext context;

  @Autowired
  private ObjectMapper serializingObjectMapper;

  @Autowired
  private SecretRepository secretRepository;

  @Autowired
  private CertificateAuthorityRepository caAuthorityRepository;

  @InjectMocks
  @Autowired
  private SecretsController secretsController;

  @Autowired
  ConfigurableEnvironment environment;

  @Mock
  private SecretGenerator<StringSecretParameters, StringSecret> stringSecretGenerator;

  @Mock
  private SecretGenerator<CertificateSecretParameters, CertificateSecret> certificateGenerator;

  @Mock
  private ResourceServerTokenServices tokenServices;

  private MockMvc mockMvc;
  private Instant frozenTime = Instant.ofEpochSecond(1400011001L);
  private SecurityContext oldContext;
  private Consumer<Long> fakeTimeSetter;

  {
    wireAndUnwire(this);
    autoTransactional(this);
    fakeTimeSetter = mockOutCurrentTimeProvider(this);

    beforeEach(() -> {
      fakeTimeSetter.accept(frozenTime.toEpochMilli());
      mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    });

    beforeEach(() -> {
      oldContext = SecurityContextHolder.getContext();
      Authentication authentication = mock(Authentication.class);
      OAuth2AuthenticationDetails authenticationDetails = mock(OAuth2AuthenticationDetails.class);
      when(authenticationDetails.getTokenValue()).thenReturn("abcde");
      when(authentication.getDetails()).thenReturn(authenticationDetails);
      OAuth2AccessToken accessToken = mock(OAuth2AccessToken.class);
      ImmutableMap<String, Object> additionalInfo = ImmutableMap.of(
          "iat", 1406568935,
          "user_name", "marissa",
          "user_id", "12345-6789a",
          "iss", 3333333333L);
      when(accessToken.getAdditionalInformation()).thenReturn(additionalInfo);
      when(accessToken.getExpiration()).thenReturn(new Date(3333333333000L));
      when(tokenServices.readAccessToken("abcde")).thenReturn(accessToken);

      SecurityContext securityContext = mock(SecurityContext.class);
      when(securityContext.getAuthentication()).thenReturn(authentication);
      SecurityContextHolder.setContext(securityContext);
    });

    afterEach(() -> {
      SecurityContextHolder.setContext(oldContext);
    });

    describe("string secrets", () -> {
      it("can save a client-provided string secret", () -> {
        String requestJson = "{\"type\":\"value\",\"credential\":\"secret contents\"}";
        String resultJson = "{" + getUpdatedAtJson() + ",\"type\":\"value\",\"credential\":\"secret contents\"}";

        expectSuccess(putRequestBuilder("/api/v1/data/secret-identifier", requestJson), resultJson);

        StringSecret expected = new StringSecret("secret contents");
        expected.setUpdatedAt(frozenTime);
        Assert.assertThat(secretRepository.findOneByName("secret-identifier").generateView(), BeanMatchers.theSameAs(expected));
      });

      it("can update a client-provided string secret", () -> {
        String requestJson = "{" + getUpdatedAtJson() + ",\"type\":\"value\",\"credential\":\"secret contents\"}";
        String requestJson2 = "{" + getUpdatedAtJson() + ",\"type\":\"value\",\"credential\":\"secret contents 2\"}";

        expectSuccess(putRequestBuilder("/api/v1/data/secret-identifier", requestJson), requestJson);
        expectSuccess(putRequestBuilder("/api/v1/data/secret-identifier", requestJson2), requestJson2);

        StringSecret expected = new StringSecret("secret contents 2");
        expected.setUpdatedAt(frozenTime);
        Assert.assertThat(secretRepository.findOneByName("secret-identifier").generateView(), BeanMatchers.theSameAs(expected));
      });

      it("can fetch a string secret", () -> {
        NamedStringSecret stringSecret = new NamedStringSecret("whatever").setValue("stringSecret contents");
        secretRepository.save(stringSecret);
        String expectedJson = json(stringSecret.generateView());

        expectSuccess(get("/api/v1/data/whatever"), expectedJson);
      });

      it("can generate string secret", () -> {
        StringSecret expectedStringSecret = new StringSecret("very-secret").setUpdatedAt(frozenTime);
        when(stringSecretGenerator.generateSecret(any(StringSecretParameters.class))).thenReturn(expectedStringSecret);

        String expectedJson = json(expectedStringSecret);
        expectSuccess(postRequestBuilder("/api/v1/data/my-secret", "{\"type\":\"value\"}"), expectedJson);
        assertThat(secretRepository.findOneByName("my-secret").generateView(), BeanMatchers.theSameAs(expectedStringSecret));
      });

      it("can generate string secret with empty parameters map", () -> {
        StringSecret expectedStringSecret = new StringSecret("very-secret").setUpdatedAt(frozenTime);
        when(stringSecretGenerator.generateSecret(any(StringSecretParameters.class))).thenReturn(expectedStringSecret);

        String expectedJson = json(expectedStringSecret);
        expectSuccess(postRequestBuilder("/api/v1/data/my-secret", "{" + getUpdatedAtJson() + ",\"type\":\"value\",\"parameters\":{}}"), expectedJson);
        assertThat(secretRepository.findOneByName("my-secret").generateView(), BeanMatchers.theSameAs(expectedStringSecret));
      });

      it("uses parameters to generate string secret", () -> {
        StringSecret expectedStringSecret = new StringSecret("long-secret").setUpdatedAt(frozenTime);
        final StringSecretParameters expectedParameters = new StringSecretParameters()
            .setLength(42)
            .setExcludeSpecial(true)
            .setExcludeNumber(true)
            .setExcludeUpper(true)
            .setType("value");
        when(stringSecretGenerator.generateSecret(refEq(expectedParameters))).thenReturn(expectedStringSecret);

        String expectedJson = json(expectedStringSecret);
        String requestJson = "{" +
            "\"type\":\"value\"," +
            "\"parameters\":{" +
            "\"length\":42, " +
            "\"exclude_special\": true," +
            "\"exclude_number\": true," +
            "\"exclude_upper\": true" +
            "}" +
            "}";
        expectSuccess(postRequestBuilder("/api/v1/data/my-secret", requestJson), expectedJson);
        assertThat(secretRepository.findOneByName("my-secret").generateView(), BeanMatchers.theSameAs(expectedStringSecret));
      });

      it("rejects requests to generate a string secret when all character types are excluded", () -> {
        String requestJson = "{" +
            "\"type\":\"value\"," +
            "\"parameters\":{" +
            "\"exclude_special\": true," +
            "\"exclude_number\": true," +
            "\"exclude_upper\": true," +
            "\"exclude_lower\": true" +
            "}" +
            "}";

        expectErrorKey(postRequestBuilder("/api/v1/data/my-secret", requestJson), HttpStatus.BAD_REQUEST, "error.excludes_all_charsets");
      });
    });

    describe("certificate secrets", () -> {
      it("can fetch a certificate secret", () -> {
        NamedCertificateSecret certificateSecret = new NamedCertificateSecret("whatever")
            .setRoot("get-ca")
            .setCertificate("get-certificate")
            .setPrivateKey("get-priv");
        secretRepository.save(certificateSecret);

        String expectedJson = json(certificateSecret.generateView());

        expectSuccess(get("/api/v1/data/whatever"), expectedJson);
      });

      it("can store a client-provided certificate", () -> {
        String requestJson = "{\"type\":\"certificate\",\"credential\":{\"root\":\"my-ca\",\"certificate\":\"my-certificate\",\"private\":\"my-priv\"}}";
        String resultJson = "{" + getUpdatedAtJson() + ",\"type\":\"certificate\",\"credential\":{\"root\":\"my-ca\",\"certificate\":\"my-certificate\",\"private\":\"my-priv\"}}";

        expectSuccess(putRequestBuilder("/api/v1/data/secret-identifier", requestJson), resultJson);

        CertificateSecret certificateSecret = new CertificateSecret("my-ca", "my-certificate", "my-priv").setUpdatedAt(frozenTime);
        assertThat(secretRepository.findOneByName("secret-identifier").generateView(), BeanMatchers.theSameAs(certificateSecret));
        assertNull(caAuthorityRepository.findOneByName("secret-identifier"));
      });

      it("storing a client-provided certificate returns JSON that contains nulls in fields the client did not provide", () -> {
        String requestJson = "{\"type\":\"certificate\",\"credential\":{\"root\":null,\"certificate\":\"my-certificate\",\"private\":\"my-priv\"}}";
        String resultJson = "{" + getUpdatedAtJson() + ",\"type\":\"certificate\",\"credential\":{\"root\":null,\"certificate\":\"my-certificate\",\"private\":\"my-priv\"}}";

        expectSuccess(putRequestBuilder("/api/v1/data/secret-identifier", requestJson), resultJson);
      });

      it("can generate certificates", () -> {
        CertificateSecret certificateSecret = new CertificateSecret("my-ca", "my-certificate", "my-priv").setUpdatedAt(frozenTime);
        when(certificateGenerator.generateSecret(any(CertificateSecretParameters.class))).thenReturn(certificateSecret);

        String requestJson = "{" +
            "\"type\":\"certificate\"," +
            "\"parameters\":{" +
            "\"common_name\":\"My Common Name\", " +
            "\"organization\": \"organization.io\"," +
            "\"organization_unit\": \"My Unit\"," +
            "\"locality\": \"My Locality\"," +
            "\"state\": \"My State\"," +
            "\"country\": \"My Country\"," +
            "\"alternative_names\": [\"My Alternative Name 1\", \"My Alternative Name 2\"]" +
            "}" +
            "}";
        String expectedJson = json(certificateSecret);
        expectSuccess(postRequestBuilder("/api/v1/data/my-cert", requestJson), expectedJson);
        assertThat(secretRepository.findOneByName("my-cert").generateView(), BeanMatchers.theSameAs(certificateSecret));
      });

      it("can store nulls in client-supplied certificate secret", () -> {
        permuteTwoEmptiesTest(null);
      });

      it("can store empty strings in client-supplied certificate secret", () -> {
        permuteTwoEmptiesTest("");
      });

      it("returns bad request (400) if all certificate fields are empty", () -> {

        new PutCertificateSimulator("", "", "")
            .setExpectation(400, "error.missing_certificate_credentials")
            .execute();
      });
    });

    describe("deleting a secret", () -> {
      beforeEach(() -> {
        NamedStringSecret stringSecret = new NamedStringSecret("whatever").setValue("super stringSecret do not tell");

        secretRepository.save(stringSecret);

        mockMvc.perform(delete("/api/v1/data/whatever"))
            .andExpect(status().isOk());
      });

      it("succeeds", () -> {
      });
    });

    describe("returns not found (404) when getting missing secrets", () -> {
      it("fails as expected", () -> {
        expectErrorKey(get("/api/v1/data/whatever"), HttpStatus.NOT_FOUND, "error.secret_not_found");
      });
    });

    describe("returns not found (404) when deleting missing secrets", () -> {
      beforeEach(() -> {
        expectErrorKey(delete("/api/v1/data/whatever"), HttpStatus.NOT_FOUND, "error.secret_not_found");
      });

      it("fails as expected", () -> {
      });
    });

    it("returns bad request (400) for PUT with empty JSON", () -> {
      expectErrorKey(putRequestBuilder("/api/v1/data/secret-identifier", "{}"), HttpStatus.BAD_REQUEST, "error.type_invalid");
    });

    it("returns bad request (400) for PUT with empty body", () -> {
      expectErrorKey(putRequestBuilder("/api/v1/data/secret-identifier", ""), HttpStatus.BAD_REQUEST, "error.type_invalid");
    });

    it("returns bad request (400) for PUT with missing type", () -> {
      expectErrorKey(putRequestBuilder("/api/v1/data/secret-identifier", "{\"credential\":\"my-secret\"}"), HttpStatus.BAD_REQUEST, "error.type_invalid");
    });

    it("returns bad request (400) for PUT with invalid JSON", () -> {
      expectErrorMessage(putRequestBuilder("/api/v1/data/secret-identifier", "{asdfasdfas}"), HttpStatus.BAD_REQUEST,
          "The request could not be fulfilled because the request path or body did not meet expectation. Please check the documentation for required formatting and retry your request.");
    });

    it("returns bad request (400) for PUT when client does not specify a value", () -> {
      expectErrorKey(putRequestBuilder("/api/v1/data/secret-identifier", "{\"type\":\"value\"}"), HttpStatus.BAD_REQUEST, "error.missing_string_secret_value");
    });

    it("returns bad request (400) for PUT without a type parameter", () -> {
      expectErrorKey(putRequestBuilder("/api/v1/data/secret-identifier", "{\"credential\":\"my-secret\"}"), HttpStatus.BAD_REQUEST, "error.type_invalid");
    });

    it("returns bad request (400) for PUT with unsupported type", () -> {
      expectErrorKey(putRequestBuilder("/api/v1/data/secret-identifier", "{\"type\":\"foo\", \"credential\":\"my-secret\"}"), HttpStatus.BAD_REQUEST, "error.type_invalid");
    });

    it("returns bad request (400) if client tries to change the type of a secret", () -> {
      NamedStringSecret stringSecret = new NamedStringSecret("secret").setValue("password");
      secretRepository.save(stringSecret);

      String requestJson = "{\"type\":\"certificate\",\"credential\":{\"root\":null,\"certificate\":\"my-certificate\",\"private\":\"my-priv\"}}";
      expectErrorKey(putRequestBuilder("/api/v1/data/secret", requestJson), HttpStatus.BAD_REQUEST, "error.type_mismatch");
    });

    it("returns bad request (400) if request body is empty", () -> {
      expectErrorKey(postRequestBuilder("/api/v1/data/secret-identifier", ""), HttpStatus.BAD_REQUEST, "error.type_invalid");
    });

    it("returns bad request (400) if JSON is empty", () -> {
      expectErrorKey(postRequestBuilder("/api/v1/data/secret-identifier", "{}"), HttpStatus.BAD_REQUEST, "error.type_invalid");
    });

    it("content negotiation and path matching are disabled", () -> {
      doPutValue("test", "abc");
      doPutValue("test.foo", "def");

      expectSuccess(get("/api/v1/data/test"), "{" + getUpdatedAtJson() + ",\"type\":\"value\",\"credential\":\"abc\"}");
    });
  }

  private void expectSuccess(RequestBuilder requestBuilder, String returnedJson) throws Exception {
    mockMvc.perform(requestBuilder)
        .andExpect(status().isOk())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8))
        .andExpect(content().json(returnedJson, true));
  }

  private void expectErrorKey(RequestBuilder requestBuilder, HttpStatus httpStatus, String errorKey) throws Exception {
    final String errorMessage = messageSource.getMessage(errorKey, new Object[0], Locale.getDefault());
    expectErrorMessage(requestBuilder, httpStatus, errorMessage);
  }

  private void expectErrorMessage(RequestBuilder requestBuilder, HttpStatus httpStatus, String errorMessage) throws Exception {
    mockMvc.perform(requestBuilder)
        .andExpect(status().is(httpStatus.value()))
        .andExpect(content().json("{\"error\": \"" + errorMessage + "\"}"));
  }

  private String getUpdatedAtJson() {
    return "\"updated_at\":\"2014-05-13T19:56:41Z\"";
  }

  private void permuteTwoEmptiesTest(String emptyValue) throws Exception {
    new PutCertificateSimulator(emptyValue, emptyValue, "my-priv")
        .setExpectation(200)
        .execute();

    new PutCertificateSimulator("my-ca", emptyValue, emptyValue)
        .setExpectation(200)
        .execute();

    new PutCertificateSimulator(emptyValue, "my-certificate", emptyValue)
        .setExpectation(200)
        .execute();
  }

  private void doPutValue(String secretName, String secretValue) throws Exception {
    String requestJson = "{\"type\":\"value\",\"credential\":\"" + secretValue + "\"}";
    String resultJson = "{" + getUpdatedAtJson() + ",\"type\":\"value\",\"credential\":\"" + secretValue + "\"}";

    expectSuccess(putRequestBuilder("/api/v1/data/" + secretName, requestJson), resultJson);
  }

  private RequestBuilder putRequestBuilder(String path, String requestBody) {
    return put(path)
        .content(requestBody)
        .contentType(MediaType.APPLICATION_JSON_UTF8);
  }

  private RequestBuilder postRequestBuilder(String path, String requestBody) {
    return post(path)
        .content(requestBody)
        .contentType(MediaType.APPLICATION_JSON_UTF8);
  }

  private String json(Object o) throws IOException {
    return serializingObjectMapper.writeValueAsString(o);
  }

  class PutCertificateSimulator {
    private final String ca;
    private final String certificate;
    private final String privateKey;
    private int statusCode;
    private String badResponseJson;

    PutCertificateSimulator(String ca, String certificate, String privateKey) {
      this.ca = ca;
      this.certificate = certificate;
      this.privateKey = privateKey;
    }

    void execute() throws Exception {
      CertificateSecret certificateSecretForRequest = new CertificateSecret(ca, certificate, privateKey);
      CertificateSecret certificateSecretForResponse = new CertificateSecret(
          transformEmptyToNull(ca),
          transformEmptyToNull(certificate),
          transformEmptyToNull(privateKey))
          .setUpdatedAt(frozenTime);

      String requestJson = json(certificateSecretForRequest);

      boolean isHttpOk = statusCode == 200;
      ResultMatcher expectedStatus = isHttpOk ? status().isOk() : status().isBadRequest();
      ResultActions result = mockMvc.perform(putRequestBuilder("/api/v1/data/whatever", requestJson))
          .andExpect(expectedStatus);
      NamedSecret certificateFromDb = secretRepository.findOneByName("whatever");

      if (isHttpOk) {
        assertThat(certificateFromDb.generateView(), BeanMatchers.theSameAs(certificateSecretForResponse));
      } else {
        assertNull(certificateFromDb);
        result.andExpect(content().json(badResponseJson));
      }
    }

    private String transformEmptyToNull(String param) {
      return "".equals(param) ? null : param;
    }

    PutCertificateSimulator setExpectation(int statusCode) {
      return setExpectation(statusCode, null);
    }

    PutCertificateSimulator setExpectation(int statusCode, String errorKey) {
      this.statusCode = statusCode;
      if (errorKey != null) {
        final String errorMessage = messageSource.getMessage(errorKey, new Object[0], Locale.getDefault());
        badResponseJson = "{\"error\": \"" + errorMessage + "\"}";
      }
      return this;
    }
  }
}
