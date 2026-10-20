package io.freemytv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.widget.Button;

/** Native buttons retain remote focus and accessible names. */
final class PlayerIcon extends Button {
    private final String symbol;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    PlayerIcon(Context context, String symbol, String label, android.view.View.OnClickListener action) {
        super(context); this.symbol = symbol; setText(label); setContentDescription(label);
        setId(android.view.View.generateViewId()); setBackground(TvWidgets.background(context)); setBackgroundTintList(null);
        setStateListAnimator(null); setOnClickListener(action); setFocusable(true); setPadding(0, 0, 0, 0);
    }
    @Override protected void onDraw(Canvas canvas) {
        float unit = Math.min(getWidth(), getHeight()) / 48f, x = getWidth() / 2f, y = getHeight() / 2f;
        paint.setColor(isFocused() || isPressed() ? 0xFF111115 : 0xFFF5F5F7); paint.setStrokeWidth(2.2f * unit); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStyle(Paint.Style.STROKE);
        if ("play".equals(symbol)) {
            if (getText().toString().equals(Ui.tr("Pausar", "Pause"))) {
                canvas.drawLine(x - 5 * unit, y - 9 * unit, x - 5 * unit, y + 9 * unit, paint);
                canvas.drawLine(x + 5 * unit, y - 9 * unit, x + 5 * unit, y + 9 * unit, paint);
            } else {
                path.reset(); path.moveTo(x - 6 * unit, y - 10 * unit); path.lineTo(x + 10 * unit, y); path.lineTo(x - 6 * unit, y + 10 * unit); path.close();
                paint.setStyle(Paint.Style.FILL); canvas.drawPath(path, paint);
            }
        } else if ("back".equals(symbol)) {
            canvas.drawLine(x + 7 * unit, y - 9 * unit, x - 3 * unit, y, paint); canvas.drawLine(x - 3 * unit, y, x + 7 * unit, y + 9 * unit, paint);
        } else if ("options".equals(symbol)) {
            paint.setStyle(Paint.Style.FILL); for (int i = -1; i <= 1; i++) canvas.drawCircle(x + i * 7 * unit, y, 1.8f * unit, paint);
        } else {
            canvas.drawArc(x - 11 * unit, y - 11 * unit, x + 11 * unit, y + 11 * unit, "rewind".equals(symbol) ? -80 : -100, "rewind".equals(symbol) ? -285 : 285, false, paint);
            paint.setStyle(Paint.Style.FILL); paint.setTextSize(12 * unit); paint.setTypeface(Ui.medium); paint.setTextAlign(Paint.Align.CENTER); canvas.drawText("10", x, y + 4 * unit, paint);
        }
    }
}
