package com.bgeforce.tv;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Small vector icons: no image requests, bitmap allocations or animation loop. */
final class StreamGlyph extends Drawable {
  private final String kind;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path path = new Path();

  StreamGlyph(String kind) {
    this.kind = kind;
    paint.setColor(0xffdddddd);
    paint.setStrokeWidth(1.8f);
  }

  public void draw(Canvas c) {
    c.save();
    c.translate(getBounds().left, getBounds().top);
    c.scale(getBounds().width() / 24f, getBounds().height() / 24f);
    paint.setStyle(Paint.Style.STROKE);
    if (kind.equals("keyboard")) {
      c.drawRect(2, 5, 22, 19, paint);
      paint.setStrokeWidth(1.2f);
      for (int y = 8; y <= 11; y += 3) for (int x = 5; x <= 19; x += 3) c.drawPoint(x, y, paint);
      c.drawLine(8, 16, 16, 16, paint);
    } else if (kind.equals("mouse")) {
      c.drawRoundRect(5, 2, 19, 22, 7, 7, paint);
      c.drawLine(5, 10, 19, 10, paint);
      c.drawLine(12, 2, 12, 10, paint);
    } else if (kind.equals("audio")) {
      path.reset();
      path.moveTo(3, 9);
      path.lineTo(7, 9);
      path.lineTo(12, 5);
      path.lineTo(12, 19);
      path.lineTo(7, 15);
      path.lineTo(3, 15);
      path.close();
      c.drawPath(path, paint);
      c.drawArc(8, 5, 23, 19, -55, 110, false, paint);
    } else if (kind.equals("more")) {
      paint.setStyle(Paint.Style.FILL);
      for (int y = 5; y <= 19; y += 7) c.drawCircle(12, y, 1.8f, paint);
    } else if (kind.equals("cursor")) {
      path.reset();
      path.moveTo(2, 1);
      path.lineTo(3, 20);
      path.lineTo(8, 15);
      path.lineTo(12, 23);
      path.lineTo(16, 21);
      path.lineTo(12, 13);
      path.lineTo(20, 13);
      path.close();
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(Color.BLACK);
      c.drawPath(path, paint);
      paint.setStyle(Paint.Style.STROKE);
      paint.setColor(Color.WHITE);
      paint.setStrokeWidth(1.2f);
      c.drawPath(path, paint);
    } else {
      c.drawLine(15, 4, 7, 12, paint);
      c.drawLine(7, 12, 15, 20, paint);
    }
    c.restore();
  }

  public void setAlpha(int alpha) {
    paint.setAlpha(alpha);
  }

  public void setColorFilter(ColorFilter filter) {
    paint.setColorFilter(filter);
  }

  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
