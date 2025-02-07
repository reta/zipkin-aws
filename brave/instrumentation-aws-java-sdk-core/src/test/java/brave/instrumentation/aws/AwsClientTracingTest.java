/*
 * Copyright 2016-2024 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package brave.instrumentation.aws;

import brave.handler.MutableSpan;
import brave.http.HttpTracing;
import brave.test.ITRemote;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBAsyncClientBuilder;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import java.io.IOException;
import java.util.Collections;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables;
import uk.org.webcompere.systemstubs.jupiter.SystemStub;
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SystemStubsExtension.class)
class AwsClientTracingTest extends ITRemote {
  public MockWebServer mockServer = new MockWebServer();

  @SystemStub
  private EnvironmentVariables variables = new EnvironmentVariables();
  private AmazonDynamoDB dbClient;
  private AmazonS3 s3Client;

  @BeforeEach void setup() {
    String endpoint = "http://localhost:" + mockServer.getPort();
    HttpTracing httpTracing = HttpTracing.create(tracing);
    AmazonDynamoDBClientBuilder clientBuilder = AmazonDynamoDBClientBuilder.standard()
        .withCredentials(
            new AWSStaticCredentialsProvider(new BasicAWSCredentials("access", "secret")))
        .withEndpointConfiguration(
            new AwsClientBuilder.EndpointConfiguration(endpoint, "us-east-1"));

    dbClient = AwsClientTracing.create(httpTracing).build(clientBuilder);

    s3Client = AwsClientTracing.create(httpTracing).build(AmazonS3ClientBuilder.standard()
        .withCredentials(
            new AWSStaticCredentialsProvider(new BasicAWSCredentials("access", "secret")))
        .withEndpointConfiguration(
            new AwsClientBuilder.EndpointConfiguration(endpoint, "us-east-1"))
        .enableForceGlobalBucketAccess());
  }

  @Test void testSpanCreatedAndTagsApplied() throws InterruptedException {
    mockServer.enqueue(createDeleteItemResponse());

    dbClient.deleteItem("test", Collections.singletonMap("key", new AttributeValue("value")));

    MutableSpan httpSpan = testSpanHandler.takeRemoteSpan(brave.Span.Kind.CLIENT);
    assertThat(httpSpan.remoteServiceName()).isEqualTo("AmazonDynamoDBv2");
    assertThat(httpSpan.name()).isEqualTo("DeleteItem");
    assertThat(httpSpan.tags().get("aws.request_id")).isEqualTo("abcd");

    MutableSpan sdkSpan = testSpanHandler.takeLocalSpan();
    assertThat(sdkSpan.name()).isEqualTo("aws-sdk");
  }

  @Test void buildingAsyncClientWithEmptyConfigDoesNotThrowExceptions() {
    HttpTracing httpTracing = HttpTracing.create(tracing);
    variables.set("AWS_REGION", "us-east-1");

    AwsClientTracing.create(httpTracing).build(AmazonDynamoDBAsyncClientBuilder.standard());
  }

  @Test void testInternalAwsRequestsDoNotThrowNPE() throws InterruptedException {
    // Responds to the internal HEAD request
    mockServer.enqueue(new MockResponse()
        .setResponseCode(400)
        .addHeader("x-amz-request-id", "abcd"));

    mockServer.enqueue(getExistsResponse());

    s3Client.doesBucketExistV2("Test-Bucket");

    // The HEAD request is also recorded
    MutableSpan errorSpan = testSpanHandler.takeRemoteSpanWithError(brave.Span.Kind.CLIENT);
    assertThat(errorSpan.remoteServiceName()).isEqualToIgnoringCase("amazon s3");
    assertThat(errorSpan.name()).isEqualToIgnoringCase("HeadBucket");
    assertThat(errorSpan.tags().get("aws.request_id")).isEqualToIgnoringCase("abcd");

    MutableSpan errorSdkSpan = testSpanHandler.takeLocalSpan();
    assertThat(errorSdkSpan.name()).isEqualToIgnoringCase("aws-sdk");

    MutableSpan httpSpan = testSpanHandler.takeRemoteSpan(brave.Span.Kind.CLIENT);
    assertThat(httpSpan.remoteServiceName()).isEqualToIgnoringCase("amazon s3");
    assertThat(httpSpan.name()).isEqualToIgnoringCase("getbucketacl");
    assertThat(httpSpan.tags().get("aws.request_id")).isEqualToIgnoringCase("abcd");

    MutableSpan sdkSpan = testSpanHandler.takeLocalSpan();
    assertThat(sdkSpan.name()).isEqualToIgnoringCase("aws-sdk");
  }

  private MockResponse createDeleteItemResponse() {
    MockResponse response = new MockResponse();
    response.setBody("{}");
    response.addHeader("x-amzn-RequestId", "abcd");
    return response;
  }

  private MockResponse getExistsResponse() {
    return new MockResponse().setBody("<AccessControlPolicy>\n"
        + "  <Owner>\n"
        + "    <ID>75aa57f09aa0c8caeab4f8c24e99d10f8e7faeebf76c078efc7c6caea54ba06a</ID>\n"
        + "    <DisplayName>CustomersName@amazon.com</DisplayName>\n"
        + "  </Owner>\n"
        + "  <AccessControlList>\n"
        + "    <Grant>\n"
        + "      <Grantee xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
        + "\t\t\txsi:type=\"CanonicalUser\">\n"
        + "        <ID>75aa57f09aa0c8caeab4f8c24e99d10f8e7faeebf76c078efc7c6caea54ba06a</ID>\n"
        + "        <DisplayName>CustomersName@amazon.com</DisplayName>\n"
        + "      </Grantee>\n"
        + "      <Permission>FULL_CONTROL</Permission>\n"
        + "    </Grant>\n"
        + "  </AccessControlList>\n"
        + "</AccessControlPolicy> ")
        .setResponseCode(200)
        .addHeader("x-amz-request-id", "abcd");
  }

  @AfterEach void afterEachTest() throws IOException {
    mockServer.close();
  }
}
