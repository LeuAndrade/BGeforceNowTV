package com.bgeforce.tv;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Local vector navigation; no emoji fallback, fonts or bitmap allocation. */
final class TvGlyph extends Drawable {
  private final String kind;
  private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

  TvGlyph(String kind) {
    this.kind = kind;
  }

  public void draw(Canvas c) {
    int save = c.save();
    c.translate(getBounds().left, getBounds().top);
    c.scale(getBounds().width() / 24f, getBounds().height() / 24f);
    p.setColor(0xffe4eaf3);
    p.setStrokeWidth(1.7f);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeCap(Paint.Cap.ROUND);
    p.setStrokeJoin(Paint.Join.ROUND);
    Path path = new Path();
    switch (kind) {
      case "home":
        path.moveTo(3, 11);
        path.lineTo(12, 3.5f);
        path.lineTo(21, 11);
        path.moveTo(5.5f, 9);
        path.lineTo(5.5f, 20);
        path.lineTo(10, 20);
        path.lineTo(10, 14);
        path.lineTo(14, 14);
        path.lineTo(14, 20);
        path.lineTo(18.5f, 20);
        path.lineTo(18.5f, 9);
        c.drawPath(path, p);
        break;
      case "library":
        c.drawRoundRect(2.5f, 7, 21.5f, 18, 4, 4, p);
        c.drawLine(6, 12, 11, 12, p);
        c.drawLine(8.5f, 9.5f, 8.5f, 14.5f, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(16, 11, 1, p);
        c.drawCircle(18.5f, 13.5f, 1, p);
        break;
      case "catalog":
        for (int y = 4; y < 20; y += 9)
          for (int x = 4; x < 20; x += 9) c.drawRoundRect(x, y, x + 6, y + 6, 1.2f, 1.2f, p);
        break;
      case "settings":
        c.drawCircle(12, 12, 6.3f, p);
        c.drawCircle(12, 12, 2.6f, p);
        for (int i = 0; i < 8; i++) {
          c.save();
          c.rotate(i * 45, 12, 12);
          c.drawLine(12, 2.5f, 12, 5.5f, p);
          c.restore();
        }
        break;
      case "search":
        c.drawCircle(10.5f, 10.5f, 6.2f, p);
        c.drawLine(15, 15, 21, 21, p);
        break;
      default:
        c.drawCircle(12, 12, 8, p);
    }
    c.restoreToCount(save);
  }

  public void setAlpha(int a) {
    p.setAlpha(a);
  }

  public void setColorFilter(ColorFilter f) {
    p.setColorFilter(f);
  }

  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
