package com.bgeforce.tv;

import java.text.Normalizer;
import java.util.*;

/** One account shelf; provider shelves remain driven by live metadata. */
final class HomeSections {
  static List<Catalog.Shelf> build(List<Game> library, List<Catalog.Shelf> remote, String title) {
    List<Catalog.Shelf> shelves = new ArrayList<>();
    if (!library.isEmpty()) {
      List<Game> games = new ArrayList<>(library);
      Collections.sort(
          games,
          (a, b) -> {
            int order = b.lastPlayed().compareTo(a.lastPlayed());
            return order != 0 ? order : a.title.compareToIgnoreCase(b.title);
          });
      shelves.add(
          new Catalog.Shelf(
              "account-library",
              title,
              new ArrayList<>(games.subList(0, Math.min(40, games.size())))));
    }
    for (Catalog.Shelf shelf : remote) {
      String key = normalize(shelf.id + " " + shelf.title);
      String compact = key.replaceAll("[^a-z0-9]", "");
      if (compact.contains("lastplayed")
          || compact.contains("playagain")
          || compact.contains("continueplaying")
          || key.contains("jogados recentemente")
          || key.contains("recentemente jogados")
          || shelf.id.equalsIgnoreCase("MRU")
          || key.contains("account-recent")
          || key.contains("recentlyplayed")
          || key.contains("recently played")
          || key.contains("lastplayed")
          || key.contains("play again")
          || key.contains("continue playing")
          || key.contains("volte a jogar")
          || key.contains("volver a jugar")) continue;
      if (!library.isEmpty()
          && (key.contains("account-library") || normalize(shelf.title).equals(normalize(title))))
        continue;
      shelves.add(shelf);
    }
    return shelves;
  }

  private static String normalize(String s) {
    return Normalizer.normalize(s, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT);
  }
}
