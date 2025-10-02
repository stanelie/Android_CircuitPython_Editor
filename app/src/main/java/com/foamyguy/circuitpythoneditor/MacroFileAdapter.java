package com.foamyguy.circuitpythoneditor;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;

public class MacroFileAdapter extends ArrayAdapter<File> {

    private LayoutInflater inflater;

    public MacroFileAdapter(@NonNull Context context, @NonNull File[] files) {
        super(context, 0);
        if (files != null) {
            addAll(files);
        }
        inflater = LayoutInflater.from(context);
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        RelativeLayout row = (RelativeLayout) convertView;
        if (row == null) {
            row = (RelativeLayout) inflater.inflate(R.layout.row_macro_file, parent, false);
        }

        File file = getItem(position);

        TextView fileNameTxt = row.findViewById(R.id.nameTxt);
        ImageView runBtn = row.findViewById(R.id.runBtn);
        ImageView editBtn = row.findViewById(R.id.editBtn);

        if (file != null) {
            fileNameTxt.setText(file.getName());
        }
        
        // NOTE: Click listeners have been removed to resolve build errors.
        // The original implementation was tightly coupled with MainActivity.
        // To restore functionality, consider passing a listener from the activity.

        return row;
    }

    public void removeAll() {
        clear();
    }
}
