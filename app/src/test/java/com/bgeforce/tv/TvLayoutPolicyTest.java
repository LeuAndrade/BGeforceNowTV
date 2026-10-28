package com.bgeforce.tv;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class TvLayoutPolicyTest {
  private Game game(String id, String time) {
    return new Game(
        Json.o(
            "id",
            id,
            "title",
            id,
            "variants",
            Json.a(
                Json.o(
                    "id",
                    "12345",
                    "gfn",
                    Json.o("library", Json.o("status", "MANUAL", "lastPlayedDate", time))))));
  }

  @Test
  public void homeHasOneLibraryInRecentPlayedOrderWithoutAPlayAgainShelf() {
    Game a = game("A", "2026-01-01"), b = game("B", "2026-10-28");
    List<Catalog.Shelf> rows =
        HomeSections.build(
            Arrays.asList(a, b),
            Arrays.asList(
                new Catalog.Shelf("lastplayed", "Volte a jogar", Arrays.asList(a)),
                new Catalog.Shelf("new", "Adicionados recentemente", Arrays.asList(b))),
            "Minha biblioteca");
    assertEquals(2, rows.size());
    assertEquals("account-library", rows.get(0).id);
    assertEquals("B", rows.get(0).games.get(0).id);
    assertEquals("new", rows.get(1).id);
  }

  @Test
  public void officialPromotionsAndRecentlyAddedAreNotRemoved() {
    List<Catalog.Shelf> remote =
        Arrays.asList(
            new Catalog.Shelf("gfnthursday", "Quinta GFN", Collections.emptyList()),
            new Catalog.Shelf("new", "Adicionados recentemente", Collections.emptyList()));
    assertEquals(remote, HomeSections.build(Collections.emptyList(), remote, "Minha biblioteca"));
  }

  @Test
  public void squareCoverPrefersBoxArtButRetainsHero() {
    Game g =
        new Game(
            Json.o(
                "images",
                Json.o(
                    "TV_BANNER", "wide", "GAME_BOX_ART", "cover", "MARQUEE_HERO_IMAGE", "hero")));
    assertEquals("cover", g.cover);
    assertEquals("hero", g.hero);
    assertEquals("wide", g.image);
  }

  @Test
  public void closeLabelsArePresentAndSearchRulesRetained() {
    assertEquals("Fechar sessão", new L(new Locale("pt", "BR")).t("closeSession"));
    assertEquals("Cerrar sesión de juego", new L(new Locale("es")).t("closeSession"));
    assertTrue(Navigation.searchVisible("home"));
    assertFalse(Navigation.searchVisible("settings"));
  }

  @Test
  public void providerRecentPlayAliasesDoNotCreateDuplicateHomeRows() {
    List<Catalog.Shelf> rows =
        HomeSections.build(
            Collections.emptyList(),
            Arrays.asList(
                new Catalog.Shelf("mru", "Jogados recentemente", Collections.emptyList()),
                new Catalog.Shelf("play-again", "Replay", Collections.emptyList()),
                new Catalog.Shelf("continue-playing", "Continue", Collections.emptyList())),
            "Minha biblioteca");
    assertTrue(rows.isEmpty());
  }
}
