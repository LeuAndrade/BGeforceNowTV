package com.bgeforce.tv;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;

public class AccountPolicyTest {
  private String jwt(long exp) {
    return "e30."
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                Json.o("exp", exp, "sub", "fixture-user")
                    .toString()
                    .getBytes(StandardCharsets.UTF_8))
        + ".signature";
  }

  @Test
  public void oauthAccessIsNeverPreferredOverIdentity() {
    assertEquals(
        "identity", TokenPolicy.gfn(Json.o("access_token", "oauth", "id_token", "identity")));
  }

  @Test
  public void oldAccountsWithoutIdentityRetainSupportedFallback() {
    assertEquals("oauth", TokenPolicy.gfn(Json.o("access_token", "oauth")));
  }

  @Test
  public void identityExpiryCanPrecedeOauthExpiry() {
    long now = 1000000;
    assertTrue(TokenPolicy.needsRefresh(Json.o("id_token", jwt(1050)), 99999999, now));
    assertFalse(TokenPolicy.needsRefresh(Json.o("id_token", jwt(9000)), 99999999, now));
  }

  @Test
  public void refreshNeverKeepsExpiredIdentity() {
    JSONObject merged =
        TokenPolicy.merge(
            Json.o("id_token", jwt(500), "refresh_token", "old-refresh"),
            Json.o("access_token", "new-access"),
            1000000);
    assertEquals("new-access", TokenPolicy.gfn(merged));
    assertEquals("old-refresh", Json.s(merged, "refresh_token"));
  }

  @Test
  public void refreshPreservesValidIdentityAndRotatedRefreshToken() {
    String identity = jwt(9000);
    JSONObject merged =
        TokenPolicy.merge(
            Json.o("id_token", identity, "refresh_token", "old"),
            Json.o("access_token", "new-access", "refresh_token", "rotated"),
            1000000);
    assertEquals(identity, TokenPolicy.gfn(merged));
    assertEquals("rotated", Json.s(merged, "refresh_token"));
  }

  @Test
  public void malformedIdentityDoesNotCrashClaimReading() {
    assertEquals(0, TokenPolicy.claims("not-a-jwt").length());
  }

  @Test
  public void sidebarHasExactlyRequestedDestinations() {
    assertArrayEquals(new String[] {"home", "library", "catalog", "settings"}, Navigation.SIDEBAR);
  }

  @Test
  public void searchIsOnlyShownOnTheThreeCatalogScreens() {
    for (String s : new String[] {"home", "library", "catalog"})
      assertTrue(Navigation.searchVisible(s));
    for (String s : new String[] {"settings", "details", "connections", "network", "recent"})
      assertFalse(Navigation.searchVisible(s));
  }

  @Test
  public void librarySearchRemainsScopedToLibrary() {
    assertTrue(Navigation.librarySearch("library"));
    assertFalse(Navigation.librarySearch("catalog"));
  }

  @Test
  public void nestedAccountPagesKeepSettingsSelected() {
    assertEquals("settings", Navigation.active("connections"));
    assertEquals("settings", Navigation.active("network"));
  }
}
