package com.bgeforce.tv;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import org.json.JSONObject;

/** Do not copy the reference client's hard-coded adult age into another user's account. */
final class AgePolicy {
  static final long CONFIRMATION_LIFETIME = 365L * 24 * 60 * 60 * 1000;

  private AgePolicy() {}

  static int number(Object value) {
    if (!(value instanceof Number) && !(value instanceof String)) return -1;
    String text = String.valueOf(value);
    if (!text.matches("[0-9]{1,3}")) return -1;
    int age = Integer.parseInt(text);
    return age <= 120 ? age : -1;
  }

  static int account(JSONObject user, long now) {
    if (user == null) return -1;
    for (String field : new String[] {"age", "userAge", "user_age"}) {
      int age = number(user.opt(field));
      if (age >= 0) return age;
    }
    String birthdate = Json.s(user, "birthdate");
    if (!birthdate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return -1;
    try {
      Calendar birth = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
      birth.setLenient(false);
      birth.clear();
      birth.set(
          Integer.parseInt(birthdate.substring(0, 4)),
          Integer.parseInt(birthdate.substring(5, 7)) - 1,
          Integer.parseInt(birthdate.substring(8, 10)));
      if (birth.getTimeInMillis() > now) return -1;
      Calendar today = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
      today.setTimeInMillis(now);
      int age = today.get(Calendar.YEAR) - birth.get(Calendar.YEAR);
      if (today.get(Calendar.MONTH) < birth.get(Calendar.MONTH)
          || (today.get(Calendar.MONTH) == birth.get(Calendar.MONTH)
              && today.get(Calendar.DAY_OF_MONTH) < birth.get(Calendar.DAY_OF_MONTH))) age--;
      return age >= 0 && age <= 120 ? age : -1;
    } catch (IllegalArgumentException error) {
      return -1;
    }
  }

  static int saved(JSONObject session, long now) {
    int age = number(session.opt("launchAge"));
    long confirmed = session.optLong("launchAgeConfirmedAt", 0);
    return confirmed > 0 && now >= confirmed && now - confirmed < CONFIRMATION_LIFETIME ? age : -1;
  }
}
