package com.foamyguy.circuitpythoneditor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    public static final String TAG = "CircuitPythonEditor";
    final char ctrlC = '\u0003';
    final char ctrlD = '\u0004';
    final String upArrow = "\u001b[A";
    final String downArrow = "\u001b[B";
    final char tab = '\t';

    private UsbService usbService;
    private EditText display;
    private EditText editText;
    private ProgressBar mainProgress;
    private MyHandler mHandler;

    private LineNumberEditText editorTxt;
    private TextView lineNumbersTxt;

    private Runnable tabResultDoneRun;
    private StringBuilder tempTabResult = new StringBuilder();

    private StringBuilder alreadySent = new StringBuilder();

    private RelativeLayout macroLyt;
    private RelativeLayout macroEditorLyt;
    private RelativeLayout newMacroNameLyt;
    private LineNumberEditText macroEditorTxt;
    private TextView macroLineNumbersTxt;
    private MacroFileAdapter macroFileAdapter;
    private String currentlyEditingMacro = "";

    // File Picker State
    private String currentFilePath = "/code.py";
    private List<FileInfo> deviceFiles = new ArrayList<>();
    private FileAdapter fileAdapter;
    private AlertDialog filePickerDialog;
    private String currentPath = "/";

    // State flags for device communication
    private boolean listingFiles = false;
    private boolean isLoading = false;
    private boolean isSaving = false;
    private boolean isInREPL = false;
    private boolean isReadyForWrite = false;
    private boolean waitingOnHistoryResult = false;
    private boolean sentUp = false;
    private boolean waitingOnTabResult = false; // New flag for tab completion

    // Terminal Scrolling
    private ScrollView terminalScroller;

    ViewPager mainPager;

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case UsbService.ACTION_USB_PERMISSION_GRANTED:
                    Toast.makeText(context, "USB Ready", Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_USB_PERMISSION_NOT_GRANTED:
                    Toast.makeText(context, "USB Permission not granted", Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_NO_USB:
                    Toast.makeText(context, "No USB connected", Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_USB_DISCONNECTED:
                    Toast.makeText(context, "USB disconnected", Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_USB_NOT_SUPPORTED:
                    Toast.makeText(context, "USB device not supported", Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    };

    private final ServiceConnection usbConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName arg0, IBinder arg1) {
            usbService = ((UsbService.UsbBinder) arg1).getService();
            usbService.setHandler(mHandler);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            usbService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mHandler = new MyHandler(this);

        MainPagerAdapter mAdapter = new MainPagerAdapter();
        mainPager = (ViewPager) findViewById(R.id.pager);
        mainPager.setAdapter(mAdapter);

        mainProgress = (ProgressBar) findViewById(R.id.mainProgress);

        tabResultDoneRun = new Runnable() {
            @Override
            public void run() {
                if (!tempTabResult.toString().contains("Traceback") && !tempTabResult.toString().contains("       ")) {
                    editText.setText("");
                    String[] lines = display.getText().toString().split("\n");
                    String lastLine = lines[lines.length - 1];
                    if (lastLine.length() > 4) {
                        editText.append(lastLine.substring(4));
                    }
                    alreadySent = new StringBuilder(editText.getText().toString());
                }
                tempTabResult.setLength(0);
                waitingOnTabResult = false; // Reset the flag
            }
        };

        File macrosDir = new File(getFilesDir() + "/macros/");
        if (!macrosDir.exists()) {
            macrosDir.mkdir();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        setFilters();
        startService(UsbService.class, usbConnection, null);
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterReceiver(mUsbReceiver);
        unbindService(usbConnection);
    }

    private void startService(Class<?> service, ServiceConnection serviceConnection, Bundle extras) {
        if (!UsbService.SERVICE_CONNECTED) {
            Intent startService = new Intent(this, service);
            if (extras != null && !extras.isEmpty()) {
                for (String key : extras.keySet()) {
                    String extra = extras.getString(key);
                    startService.putExtra(key, extra);
                }
            }
            startService(startService);
        }
        Intent bindingIntent = new Intent(this, service);
        bindService(bindingIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void setFilters() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbService.ACTION_USB_PERMISSION_GRANTED);
        filter.addAction(UsbService.ACTION_NO_USB);
        filter.addAction(UsbService.ACTION_USB_DISCONNECTED);
        filter.addAction(UsbService.ACTION_USB_NOT_SUPPORTED);
        filter.addAction(UsbService.ACTION_USB_PERMISSION_NOT_GRANTED);
        registerReceiver(mUsbReceiver, filter);
    }

    private boolean isOperationInProgress() {
        return isLoading || isSaving || listingFiles;
    }

    public void showFilePicker(View view) {
        if (!isDeviceConnected()) {
            Toast.makeText(this, "No USB device connected", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isOperationInProgress()) {
            Toast.makeText(view.getContext(), "Please wait for current operation to complete", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isInREPL) {
            sendCtrlD(null);
            new Handler(Looper.getMainLooper()).postDelayed(() -> showFilePicker(view), 250);
            return;
        }
        mainProgress.setIndeterminate(true);
        listingFiles = true;
        sendCtrlC();
    }

    private void showFilePickerDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select a file");

        ListView fileListView = new ListView(this);
        fileAdapter = new FileAdapter(this, deviceFiles);
        fileListView.setAdapter(fileAdapter);

        builder.setView(fileListView);
        filePickerDialog = builder.create();

        fileListView.setOnItemClickListener((parent, view, position, id) -> {
            FileInfo selectedFile = deviceFiles.get(position);
            if (selectedFile.name.equals("..")) {
                currentPath = new File(currentPath).getParent();
                if (currentPath == null || currentPath.equals("\\")) {
                    currentPath = "/";
                }
            } else if (selectedFile.isDirectory) {
                currentPath = currentPath.equals("/") ? "/" + selectedFile.name : currentPath + "/" + selectedFile.name;
            } else {
                currentFilePath = currentPath.equals("/") ? "/" + selectedFile.name : currentPath + "/" + selectedFile.name;
                filePickerDialog.dismiss();
                loadCodePy(null);
                return;
            }
            filePickerDialog.dismiss();
            mainProgress.setIndeterminate(true);
            listingFiles = true;
            sendCtrlC();
        });

        filePickerDialog.show();
    }

    public void loadCodePy(View view) {
        if (!isDeviceConnected()) {
            Toast.makeText(this, "No USB device connected", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isOperationInProgress()) {
            Toast.makeText(this, "Please wait for current operation to complete", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isInREPL) {
            sendCtrlD(null);
            new Handler(Looper.getMainLooper()).postDelayed(() -> loadCodePy(view), 250);
            return;
        }
        mainProgress.setIndeterminate(true);
        isLoading = true;
        sendCtrlC();
    }

    public void saveMainPy(View view) {
        if (!isDeviceConnected()) {
            Toast.makeText(this, "No USB device connected", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isOperationInProgress()) {
            Toast.makeText(view.getContext(), "Please wait for current operation to complete", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isInREPL) {
            new AlertDialog.Builder(this)
                    .setTitle("Warning")
                    .setMessage("Must enter REPL before saving. Please send CTRL-C, then try again.")
                    .setPositiveButton("Send CTRL-C", (dialog, which) -> sendCtrlC(null))
                    .setNegativeButton("Cancel", null)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .show();
            return;
        }

        isSaving = true;
        send("import gc");
        send("f = open('" + currentFilePath + "', 'w')");
        send("f.write('')");
        send("f.close()");
        send("f = open('" + currentFilePath + "', 'a')");

        String[] lines = editorTxt.getText().toString().replace("\r", "").split("\n");
        mainProgress.setMax(lines.length - 1);
        curIndex = 0;
        writeFileDelayed(lines);
    }

    private void send(String text) {
        if (usbService != null) {
            usbService.write((text + "\r\n").getBytes());
        }
    }

    private void sendCtrlC() {
        if (usbService != null) {
            usbService.write(String.valueOf(ctrlC).getBytes());
            isInREPL = true;
        }
    }

    public void sendCtrlC(View view) {
        sendCtrlC();
        new Handler(Looper.getMainLooper()).postDelayed(() -> usbService.write("\n".getBytes()), 100);
    }

    public void sendCtrlD(View view) {
        if (usbService != null) {
            usbService.write(String.valueOf(ctrlC).getBytes());
            isInREPL = true;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (usbService != null) {
                    usbService.write(String.valueOf(ctrlD).getBytes());
                    isInREPL = false;
                }
            }, 100);
        }
    }

    public void sendUpArrow(View view) {
        if (usbService != null) {
            usbService.write(upArrow.getBytes());
            sentUp = true;
            waitingOnHistoryResult = true;
        }
    }

    public void sendDownArrow(View view) {
        if (usbService != null) {
            usbService.write(downArrow.getBytes());
            waitingOnHistoryResult = true;
        }
    }

    public void sendTab(View view) {
        if (usbService != null) {
            String data = editText.getText().toString();
            if (alreadySent.length() > 0) {
                data = data.substring(alreadySent.length());
            }
            usbService.write((data + tab).getBytes());
            alreadySent.append(editText.getText().toString());
            waitingOnTabResult = true; // Set flag to true when tab is sent
        }
    }

    private int curIndex = 0;

    private String escapePythonString(String text) {
        text = text.replace("\\", "\\\\");
        text = text.replace("\"", "\\\"");
        return text;
    }

    public void writeFileDelayed(String[] lines) {
        if (!isDeviceConnected()) {
            Toast.makeText(this, "Device not connected.", Toast.LENGTH_SHORT).show();
            isSaving = false;
            mainProgress.setProgress(0);
            return;
        }
        if (!isReadyForWrite) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> writeFileDelayed(lines), 50);
            return;
        }
        isReadyForWrite = false;
        String escapedLine = escapePythonString(lines[curIndex]);
        send("f.write(\"" + escapedLine + "\\r\\n\")");

        if (curIndex >= lines.length - 1) {
            send("f.close()");
            isSaving = false;
            mainProgress.setProgress(0);
            curIndex = 0;
            Toast.makeText(this, "Saved to device", Toast.LENGTH_SHORT).show();
        } else {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                curIndex++;
                mainProgress.setProgress(curIndex);
                writeFileDelayed(lines);
            }, 50);
        }
    }

    private boolean isDeviceConnected() {
        UsbManager manager = (UsbManager) getSystemService(Context.USB_SERVICE);
        return manager != null && !manager.getDeviceList().isEmpty();
    }

    private void appendToTerminal(String text) {
        if (terminalScroller == null || display == null) return;

        boolean canScrollDown = terminalScroller.canScrollVertically(1);

        display.append(text);

        if (!canScrollDown) {
            terminalScroller.post(() -> terminalScroller.fullScroll(View.FOCUS_DOWN));
        }
    }

    private static class MyHandler extends Handler {
        private final WeakReference<MainActivity> mActivity;
        private final StringBuilder serialBuffer = new StringBuilder();

        MyHandler(MainActivity activity) {
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            MainActivity mainActivity = mActivity.get();
            if (mainActivity == null) return;

            if (msg.what == UsbService.MESSAGE_FROM_SERIAL_PORT) {
                String data = (String) msg.obj;
                Log.d(TAG, "RECV: " + data);
                serialBuffer.append(data);

                if (serialBuffer.toString().contains(">>>")) {
                    String fullResponse = serialBuffer.toString();
                    serialBuffer.setLength(0);

                    if (mainActivity.listingFiles) {
                        if (fullResponse.contains("os.listdir")) { // Response to our command
                            handleListFiles(mainActivity, fullResponse);
                        }
                         else { // Initial REPL prompt after Ctrl+C
                            String script = "import os; path = '" + mainActivity.currentPath + "'; l = os.listdir(path); print([(name, (os.stat(path + '/' + name)[0] & 0x4000) != 0) for name in l])";
                            mainActivity.send(script);
                        }
                    } else if (mainActivity.isLoading) {
                        if (fullResponse.contains("print(f.read())")) { // Response to our command
                            handleLoadFile(mainActivity, fullResponse);
                        } else { // Initial REPL prompt after Ctrl+C
                            mainActivity.send("f = open('" + mainActivity.currentFilePath + "', 'r')");
                            mainActivity.send("print(f.read())");
                            mainActivity.send("f.close()");
                        }
                    } else if (mainActivity.waitingOnTabResult) {
                         mainActivity.tempTabResult.append(fullResponse);
                         new Handler(Looper.getMainLooper()).post(mainActivity.tabResultDoneRun);
                    }
                    else {
                        mainActivity.appendToTerminal(fullResponse);
                        if (mainActivity.isSaving) {
                            mainActivity.isReadyForWrite = true;
                        }
                    }
                } else if (data.contains("Press any key to enter the REPL.")) {
                     mainActivity.send("a");
                } else if (!mainActivity.isLoading && !mainActivity.listingFiles) {
                    mainActivity.appendToTerminal(data);
                }
            }
        }

        private void handleListFiles(MainActivity mainActivity, String response) {
            mainActivity.listingFiles = false;
            mainActivity.runOnUiThread(() -> mainActivity.mainProgress.setIndeterminate(false));

            int listStartIndex = response.indexOf("[");
            int listEndIndex = response.lastIndexOf("]");
            if (listStartIndex != -1 && listEndIndex != -1) {
                mainActivity.deviceFiles.clear();
                String fileListData = response.substring(listStartIndex, listEndIndex + 1);
                Pattern pattern = Pattern.compile("'([^']*)', (True|False)");
                Matcher matcher = pattern.matcher(fileListData);
                while (matcher.find()) {
                    mainActivity.deviceFiles.add(new FileInfo(matcher.group(1), Boolean.parseBoolean(matcher.group(2))));
                }

                mainActivity.deviceFiles.sort(Comparator.comparing((FileInfo f) -> !f.isDirectory).thenComparing(f -> f.name));

                if (!mainActivity.currentPath.equals("/")) {
                    mainActivity.deviceFiles.add(0, new FileInfo("..", true));
                }
                mainActivity.runOnUiThread(mainActivity::showFilePickerDialog);
            } else if (response.contains("Traceback")) {
                mainActivity.runOnUiThread(() -> Toast.makeText(mainActivity, "Directory not found", Toast.LENGTH_SHORT).show());
            }
        }

        private void handleLoadFile(MainActivity mainActivity, String response) {
            mainActivity.isLoading = false;
            mainActivity.runOnUiThread(() -> mainActivity.mainProgress.setIndeterminate(false));

            String commandToStrip = "print(f.read())";
            int contentStartIndex = response.indexOf(commandToStrip);
            if (contentStartIndex != -1) {
                contentStartIndex += commandToStrip.length();
                while (contentStartIndex < response.length() && (response.charAt(contentStartIndex) == '\r' || response.charAt(contentStartIndex) == '\n')) {
                    contentStartIndex++;
                }
                int contentEndIndex = response.lastIndexOf(">>>");
                if (contentEndIndex != -1) {
                    String code = response.substring(contentStartIndex, contentEndIndex).trim();
                    mainActivity.runOnUiThread(() -> {
                        mainActivity.editorTxt.setText(code);
                        mainActivity.showLineNumbers();
                    });
                }
            } else if (response.contains("Traceback")) {
                mainActivity.runOnUiThread(() -> {
                    Toast.makeText(mainActivity, "File not found: " + mainActivity.currentFilePath, Toast.LENGTH_SHORT).show();
                    mainActivity.editorTxt.setText("");
                    mainActivity.showLineNumbers();
                });
            }
        }
    }

    private static class FileInfo {
        final String name;
        final boolean isDirectory;
        FileInfo(String name, boolean isDirectory) {
            this.name = name;
            this.isDirectory = isDirectory;
        }
    }

    private class FileAdapter extends ArrayAdapter<FileInfo> {
        FileAdapter(@NonNull Context context, List<FileInfo> files) {
            super(context, android.R.layout.simple_list_item_1, files);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            TextView view = (TextView) super.getView(position, convertView, parent);
            FileInfo fileInfo = getItem(position);
            if (fileInfo != null) {
                view.setText(fileInfo.name + (fileInfo.isDirectory ? "/" : ""));
                view.setBackgroundColor(fileInfo.isDirectory ? Color.LTGRAY : Color.TRANSPARENT);
            }
            return view;
        }
    }

    public class MainPagerAdapter extends PagerAdapter {
        @Override
        public int getCount() { return 2; }
        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) { return view == object; }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            LayoutInflater inflater = LayoutInflater.from(container.getContext());
            View page;
            if (position == 0) {
                page = inflater.inflate(R.layout.terminal_layout, container, false);
                setupTerminalView(page);
            } else {
                page = inflater.inflate(R.layout.code_editor_layout, container, false);
                setupEditorView(page);
            }
            container.addView(page);
            return page;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            container.removeView((View) object);
        }

        private void setupTerminalView(View page) {
            display = page.findViewById(R.id.terminalTxt);
            display.setTextIsSelectable(true);
            terminalScroller = page.findViewById(R.id.terminalScroller);

            editText = page.findViewById(R.id.inputEdt);
            Button sendButton = page.findViewById(R.id.buttonSend);
            sendButton.setOnClickListener(v -> {
                if (usbService == null) return;
                String data = editText.getText().toString();
                if (!data.isEmpty()) {
                    if (alreadySent.length() > 0) {
                        data = data.substring(alreadySent.length());
                    }
                    usbService.write((data + "\r\n").getBytes());
                    editText.setText("");
                    alreadySent.setLength(0);
                } else if (sentUp) {
                    usbService.write("\r\n".getBytes());
                }
            });
            setupMacroViews(page);
        }

        private void setupEditorView(View page) {
            editorTxt = page.findViewById(R.id.mainEditor);
            lineNumbersTxt = page.findViewById(R.id.editorLineNumbers);
            editorTxt.setLineNumbersText(lineNumbersTxt);
            editorTxt.setHorizontallyScrolling(true);
            editorTxt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

            editorTxt.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) { showLineNumbers(); }
            });
        }

        private void setupMacroViews(View page) {
            macroLyt = page.findViewById(R.id.macroLyt);
            macroEditorLyt = page.findViewById(R.id.macroEditorLyt);
            newMacroNameLyt = page.findViewById(R.id.newMacroNameLyt);
            macroEditorTxt = page.findViewById(R.id.macroEditor);
            macroLineNumbersTxt = page.findViewById(R.id.macroEditorLineNumbers);

            ListView macroList = page.findViewById(R.id.macroFilesLst);
            List<File> fileList = new ArrayList<>(Arrays.asList(Macro.getMacroFileList(macroList.getContext())));
            macroFileAdapter = new MacroFileAdapter(macroList.getContext(), fileList);
            macroList.setAdapter(macroFileAdapter);

            EditText newMacroNameEdt = page.findViewById(R.id.newMacroNameEdt);
            ImageView newMacroBtn = page.findViewById(R.id.newMacroBtn);
            ImageView createBtn = page.findViewById(R.id.createMacroBtn);

            newMacroBtn.setOnClickListener(v -> {
                newMacroNameLyt.setVisibility(View.VISIBLE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    newMacroNameLyt.setElevation(1001);
                }
                newMacroNameEdt.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(newMacroNameEdt, InputMethodManager.SHOW_IMPLICIT);
            });

            createBtn.setOnClickListener(v -> {
                newMacroNameLyt.setVisibility(View.GONE);
                hideKeyboard();
                File newMacroFile = new File(getFilesDir() + "/macros/" + newMacroNameEdt.getText().toString());
                try { newMacroFile.createNewFile(); } catch (IOException e) { e.printStackTrace(); }
                macroFileAdapter.clear();
                macroFileAdapter.addAll(new ArrayList<>(Arrays.asList(Macro.getMacroFileList(v.getContext()))));
                macroFileAdapter.notifyDataSetChanged();
            });
        }
    }

    private void showLineNumbers() {
        if (editorTxt == null || lineNumbersTxt == null) return;
        int lines = editorTxt.getLineCount();
        StringBuilder lineNumbersStr = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            lineNumbersStr.append(i).append("\n");
        }
        lineNumbersTxt.setText(lineNumbersStr.toString());
    }
    
    private void showMacroLineNumbers() {
        if (macroEditorTxt == null || macroLineNumbersTxt == null) return;
        int lines = macroEditorTxt.getLineCount();
        StringBuilder lineNumbersStr = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            lineNumbersStr.append(i).append("\n");
        }
        macroLineNumbersTxt.setText(lineNumbersStr.toString());
    }

    public void showMacroLyt(View view) {
        mainPager.setCurrentItem(0);
        if (macroLyt != null) {
            macroLyt.setVisibility(View.VISIBLE);
        }
        hideKeyboard();
    }

    public void showTerminalLyt(View view) {
        if (macroLyt != null && macroLyt.getVisibility() == View.VISIBLE) {
            macroLyt.setVisibility(View.GONE);
        }
        mainPager.setCurrentItem(0);
    }

    public void showEditorLyt(View view) {
        mainPager.setCurrentItem(1);
    }

    @Override
    public void onBackPressed() {
        if (macroEditorLyt != null && macroEditorLyt.getVisibility() == View.VISIBLE) {
            macroEditorLyt.setVisibility(View.GONE);
        } else if (newMacroNameLyt != null && newMacroNameLyt.getVisibility() == View.VISIBLE) {
            newMacroNameLyt.setVisibility(View.GONE);
        } else if (macroLyt != null && macroLyt.getVisibility() == View.VISIBLE) {
            macroLyt.setVisibility(View.GONE);
        } else if (mainPager != null && mainPager.getCurrentItem() == 1) { // We are in the editor
            mainPager.setCurrentItem(0); // Go back to the terminal
        } else {
            finish(); // We are in the terminal, so exit
        }
    }

    public void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Activity.INPUT_METHOD_SERVICE);
        View view = getCurrentFocus();
        if (view == null) view = new View(this);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    public void saveMacro(View view) {
        if (macroEditorTxt != null && !currentlyEditingMacro.isEmpty()) {
            String newContent = macroEditorTxt.getText().toString();
            Macro.writeMacroFile(this, currentlyEditingMacro, newContent);
            macroEditorLyt.setVisibility(View.GONE);
            hideKeyboard();
            Toast.makeText(this, "Macro saved", Toast.LENGTH_SHORT).show();
        }
    }

    public class MacroFileAdapter extends ArrayAdapter<File> {
        private LayoutInflater inflater;

        MacroFileAdapter(@NonNull Context context, List<File> files) {
            super(context, 0, files);
            inflater = LayoutInflater.from(context);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            RelativeLayout row = (RelativeLayout) convertView;
            if (row == null) {
                row = (RelativeLayout) inflater.inflate(R.layout.row_macro_file, parent, false);
            }
            TextView fileNameTxt = row.findViewById(R.id.nameTxt);
            ImageView runBtn = row.findViewById(R.id.runBtn);
            ImageView editBtn = row.findViewById(R.id.editBtn);
            File file = getItem(position);
            fileNameTxt.setText(file.getName());

            editBtn.setOnClickListener(view -> {
                if (macroEditorTxt == null || macroEditorLyt == null) return; 
                macroEditorTxt.setText(Macro.readMacroFile(view.getContext(), file.getName()));
                macroEditorLyt.setVisibility(View.VISIBLE);
                macroEditorTxt.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) { showMacroLineNumbers(); }
                    @Override public void afterTextChanged(Editable s) {}
                });
                showMacroLineNumbers();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    macroEditorLyt.setElevation(1002);
                }
                currentlyEditingMacro = file.getName();
            });

            runBtn.setOnClickListener(view -> {
                if (isInREPL) {
                    String[] lines = Macro.readMacroFile(view.getContext(), file.getName()).split("\n");
                    curIndex = 0; 
                    executeMacro(lines);
                    if(macroLyt != null) {
                        macroLyt.setVisibility(View.GONE);
                    }
                } else {
                    new AlertDialog.Builder(getContext())
                            .setTitle("Warning")
                            .setMessage("Must enter REPL before using macro. Please send CTRL-C, then try again.")
                            .setPositiveButton("Send CTRL-C", (dialog, which) -> sendCtrlC(null))
                            .setNegativeButton("Cancel", null)
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .show();
                }
            });

            fileNameTxt.setOnLongClickListener(view -> {
                new AlertDialog.Builder(getContext())
                        .setTitle("Delete entry")
                        .setMessage("Are you sure you want to delete this entry?")
                        .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                            Macro.delete(getContext(), file.getName());
                            clear();
                            addAll(new ArrayList<>(Arrays.asList(Macro.getMacroFileList(getContext()))));
                            notifyDataSetChanged();
                        })
                        .setNegativeButton(android.R.string.no, null)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .show();
                return true;
            });
            return row;
        }

        private void executeMacro(String[] macroLines) {
            if (curIndex >= macroLines.length) {
                curIndex = 0;
                return;
            }
            send(macroLines[curIndex]);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                curIndex++;
                executeMacro(macroLines);
            }, 100);
        }
    }
}