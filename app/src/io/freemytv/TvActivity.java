package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.view.KeyEvent;

/** The remote Search key opens title search from application screens. */
public abstract class TvActivity extends Activity {
    @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
        return getApplicationContext().getSharedPreferences(name, mode);
    }
    @Override public java.io.File getFilesDir() { return getApplicationContext().getFilesDir(); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SEARCH) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                if (this instanceof SearchActivity) ((SearchActivity) this).focusSearch();
                else startActivity(new Intent(this, SearchActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
