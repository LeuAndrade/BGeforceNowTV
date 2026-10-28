package com.bgeforce.tv;

/** Navigation rules shared by the native toolbar and regression tests. */
final class Navigation {
  static final String[] SIDEBAR = {"home", "library", "catalog", "settings"};

  static boolean searchVisible(String screen) {
    return screen.equals("home") || screen.equals("library") || screen.equals("catalog");
  }

  static boolean librarySearch(String screen) {
    return screen.equals("library");
  }

  static String active(String screen) {
    return screen.equals("details")
        ? "catalog"
        : screen.equals("connections") || screen.equals("network") ? "settings" : screen;
  }

  private Navigation() {}
}
