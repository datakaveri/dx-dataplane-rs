package org.cdpg.dx.auth.appid.lookup;

import io.vertx.core.Future;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cdpg.dx.auth.appid.client.AppIdVerificationClient;
import org.cdpg.dx.auth.appid.v1.AppIdPrincipalProto;
import org.cdpg.dx.auth.appid.v1.VerifyAppIdResponse;
import org.cdpg.dx.auth.v2.lookup.AppCredentialLookup;
import org.cdpg.dx.auth.v2.model.AppPrincipal;

/**
 * gRPC-backed {@link AppCredentialLookup} for the dataplane. Delegates to the existing
 * {@link AppIdVerificationClient#verify} call and maps the proto response to {@link AppPrincipal}.
 */
public final class GrpcAppCredentialLookup implements AppCredentialLookup {

  private static final Logger LOGGER = LogManager.getLogger(GrpcAppCredentialLookup.class);

  private final AppIdVerificationClient client;

  public GrpcAppCredentialLookup(AppIdVerificationClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public Future<Optional<AppPrincipal>> verify(String appId, String appSecret) {
    return client
        .verify(appId, appSecret)
        .map(this::toAppPrincipal)
        .recover(
            err -> {
              LOGGER.error("gRPC VerifyAppId transport error for appId={}: {}", appId, err.getMessage());
              return Future.failedFuture(err);
            });
  }

  private Optional<AppPrincipal> toAppPrincipal(VerifyAppIdResponse response) {
    if (!response.getSuccess()) {
      LOGGER.debug("VerifyAppId rejected: {}", response.getErrorCode());
      return Optional.empty();
    }
    AppIdPrincipalProto proto = response.getPrincipal();
    // Proto uses underscore format (e.g. "data_access"); internal scope constants use hyphens.
    List<String> scopes = proto.getScopesList().stream()
        .map(s -> s.replace('_', '-'))
        .collect(Collectors.toList());
    LOGGER.info("VerifyAppId success appId={} rawScopes={} normalizedScopes={}",
        proto.getAppId(), proto.getScopesList(), scopes);
    return Optional.of(
        new AppPrincipal(
            proto.getAppId(),
            proto.getUserId(),
            proto.getOrganisationId().isBlank() ? null : proto.getOrganisationId(),
            scopes,
            proto.getExpiresAtEpoch(),
            true));
  }
}
