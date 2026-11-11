package com.rimdroid.fragments;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.rimdroid.InstanceShortcuts;
import com.rimdroid.R;
import com.rimdroid.game.GameInstance;
import com.rimdroid.game.GameInstanceManager;
import com.rimdroid.mods.ModList;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mods of one instance, from the drawer (the screen idea from ValDroid, RimWorld's own rules
 * underneath: ModList). One list: "Active" in load order — the order the game loads them in — then
 * "Inactive" by name, with a search field for 50–100 mods. Each row has a switch and, for downloaded
 * mods, a delete button; a tap shows the mod's details. Active mods are dragged by their handle
 * (written when the finger lifts), and a long press offers up / down / top / bottom for long trips. "Sort" orders the active mods by their authors' rules (ModSorter) and shows what
 * changes and what is missing before anything is written. Core is always on; RimDroid's own
 * built-in mod is not listed. While the game runs the list is read-only: the game rewrites ModsConfig.xml when
 * it exits and would undo any change made here.
 */
public class ModsFragment extends Fragment {

    private Spinner spInstance;
    private TextView tvRunning, tvNoConfig, tvEmpty;
    private EditText etSearch;
    private RecyclerView rv;
    private final List<GameInstance> instances = new ArrayList<>();
    private final List<Object> rows = new ArrayList<>();   // String = section header, Row = a mod
    private boolean editable;
    private View btnSort, btnEnableAll, btnDisableAll, btnCopy;
    /** Dragging changes only the screen; true until the new order is written on release. */
    private boolean dragged;
    private androidx.recyclerview.widget.ItemTouchHelper dragHelper;

    /** A row: the installed mod, or null for an active entry whose mod is not installed. */
    private static final class Row {
        final String packageId;
        @Nullable final ModList.Mod mod;
        final boolean active;
        Row(String packageId, @Nullable ModList.Mod mod, boolean active) { this.packageId = packageId; this.mod = mod; this.active = active; }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mods, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        spInstance = v.findViewById(R.id.sp_mods_instance);
        tvRunning = v.findViewById(R.id.tv_mods_game_running);
        tvNoConfig = v.findViewById(R.id.tv_mods_no_config);
        tvEmpty = v.findViewById(R.id.tv_mods_empty);
        etSearch = v.findViewById(R.id.et_mods_search);
        rv = v.findViewById(R.id.rv_mods);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(adapter);
        dragHelper = new androidx.recyclerview.widget.ItemTouchHelper(dragCallback);
        dragHelper.attachToRecyclerView(rv);

        GameInstanceManager.requireSingleton().reload();
        instances.clear();
        instances.addAll(GameInstanceManager.requireSingleton().getInstances());
        List<String> names = new ArrayList<>();
        if (instances.isEmpty()) names.add(getString(R.string.no_instances));
        // Several instances: none is picked for the player (editing the wrong one's mods is too easy);
        // a single one is picked at once.
        if (instances.size() > 1) names.add(getString(R.string.choose_instance_prompt));
        for (GameInstance gi : instances) names.add(gi.getName());
        ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spInstance.setAdapter(a);
        spInstance.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View view, int pos, long id) { refresh(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a1, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a1, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refresh(); }
        });
        v.findViewById(R.id.btn_mods_install).setOnClickListener(x ->
                Navigation.findNavController(v).navigate(R.id.action_open_install));
        btnSort = v.findViewById(R.id.btn_mods_sort);
        btnSort.setOnClickListener(x -> sort());
        btnEnableAll = v.findViewById(R.id.btn_mods_enable_all);
        btnDisableAll = v.findViewById(R.id.btn_mods_disable_all);
        btnEnableAll.setOnClickListener(x -> enableAllShown());
        btnDisableAll.setOnClickListener(x -> disableAllShown());
        btnCopy = v.findViewById(R.id.btn_mods_copy);
        btnCopy.setOnClickListener(x -> chooseCopyTarget());
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();   // mods may have been installed or downloaded in the meantime
        // A fresher copy of the players' load-order rules for the next sort, at most once a day.
        com.rimdroid.mods.CommunityRules.refreshInBackground(requireContext().getApplicationContext());
    }

    @Nullable
    private GameInstance current() {
        int i = spInstance == null ? -1 : spInstance.getSelectedItemPosition();
        if (instances.size() > 1) i--;   // the "choose an instance" line comes first
        return (i >= 0 && i < instances.size()) ? instances.get(i) : null;
    }

    private void refresh() {
        if (rv == null) return;
        rows.clear();
        GameInstance gi = current();
        boolean running = InstanceShortcuts.isGameRunning(requireContext());
        tvRunning.setVisibility(running ? View.VISIBLE : View.GONE);
        if (gi == null) {
            tvNoConfig.setVisibility(View.GONE);
            // "No mods" only when there is no instance at all, not while one is still to be chosen.
            tvEmpty.setVisibility(instances.isEmpty() ? View.VISIBLE : View.GONE);
            editable = false;
            btnSort.setEnabled(false);
            btnEnableAll.setEnabled(false);
            btnDisableAll.setEnabled(false);
            btnCopy.setEnabled(false);
            adapter.notifyDataSetChanged();
            return;
        }
        File dir = new File(gi.getGamePath());
        // A new instance has no ModsConfig.xml until the game's first start: write the one the game
        // would, so its mods can be switched on right away.
        if (!running) ModList.ensureConfig(dir);
        List<ModList.Mod> mods = ModList.installed(dir);
        List<String> order = ModList.activeOrder(dir);
        tvNoConfig.setVisibility(order == null ? View.VISIBLE : View.GONE);
        editable = !running && order != null;
        btnSort.setEnabled(editable);
        btnEnableAll.setEnabled(editable);
        btnDisableAll.setEnabled(editable);
        btnCopy.setEnabled(!running);

        String q = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        Map<String, ModList.Mod> byId = ModList.byId(mods);
        Set<String> activeIds = new HashSet<>();
        List<Row> active = new ArrayList<>(), inactive = new ArrayList<>();
        if (order != null) {
            for (String id : order) {
                if (ModList.BUILTIN.equals(id) || !activeIds.add(id)) continue;
                Row r = new Row(id, byId.get(id), true);
                if (matches(r, q)) active.add(r);
            }
        }
        for (ModList.Mod m : mods) {
            if (activeIds.contains(m.packageId)) continue;
            Row r = new Row(m.packageId, m, false);
            if (matches(r, q)) inactive.add(r);
        }
        rows.add(getString(R.string.mods_active, active.size()));
        rows.addAll(active);
        rows.add(getString(R.string.mods_inactive, inactive.size()));
        rows.addAll(inactive);
        tvEmpty.setVisibility(mods.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private static boolean matches(Row r, String q) {
        if (q.isEmpty()) return true;
        if (r.packageId.contains(q)) return true;
        return r.mod != null && (r.mod.name.toLowerCase(Locale.ROOT).contains(q)
                || r.mod.author.toLowerCase(Locale.ROOT).contains(q));
    }

    private void toggle(Row r, boolean on) {
        GameInstance gi = current();
        if (gi == null) return;
        if (!ModList.setActive(new File(gi.getGamePath()), r.packageId, on))
            Toast.makeText(requireContext(), R.string.mods_switch_failed, Toast.LENGTH_SHORT).show();
        refresh();
    }

    // ---- enable / disable everything shown (players load 150 mods at once) ----

    /** The rows on screen now (all mods, or what the search leaves) that are active or not. */
    private List<Row> shown(boolean active) {
        List<Row> out = new ArrayList<>();
        for (Object o : rows) if (o instanceof Row && ((Row) o).active == active) out.add((Row) o);
        return out;
    }

    /** Every inactive mod shown goes to the end of the load order, as the game does with one. */
    private void enableAllShown() {
        GameInstance gi = current();
        if (gi == null || !editable) return;
        File dir = new File(gi.getGamePath());
        List<String> order = ModList.activeOrder(dir);
        if (order == null) return;
        List<Row> off = shown(false);
        if (off.isEmpty()) return;
        for (Row r : off) if (!order.contains(r.packageId)) order.add(r.packageId);
        if (!ModList.writeOrder(dir, order))
            Toast.makeText(requireContext(), R.string.mods_switch_failed, Toast.LENGTH_SHORT).show();
        else
            Toast.makeText(requireContext(), getString(R.string.mods_enabled_n, off.size()), Toast.LENGTH_LONG).show();
        refresh();
    }

    /** Every active mod shown is switched off, except Core and the DLC (as the game keeps them);
     *  their load order is lost, so it asks first. */
    // ---- copy the mods to another instance ----

    /** Every downloaded mod of this instance into another one; optionally its load order too. */
    private void chooseCopyTarget() {
        GameInstance from = current();
        if (from == null) return;
        List<GameInstance> targets = new ArrayList<>();
        for (GameInstance gi : instances) if (gi != from && !gi.getName().equals(from.getName())) targets.add(gi);
        if (targets.isEmpty()) {
            Toast.makeText(requireContext(), R.string.mods_copy_no_target, Toast.LENGTH_LONG).show();
            return;
        }
        // The game version next to each name: mods usually carry folders for several versions, but
        // a 1.6 mod list copied into 1.7 is worth a second look.
        String[] names = new String[targets.size()];
        for (int i = 0; i < names.length; i++) {
            String ver = ModList.gameVersion(new File(targets.get(i).getGamePath()));
            names[i] = targets.get(i).getName() + (ver.isEmpty() ? "" : "  (" + ver + ")");
        }
        final int[] picked = { 0 };
        View box = getLayoutInflater().inflate(R.layout.dialog_mods_copy, null);
        android.widget.ListView list = box.findViewById(R.id.lv_mods_copy_targets);
        list.setChoiceMode(android.widget.ListView.CHOICE_MODE_SINGLE);
        list.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_single_choice, names));
        list.setItemChecked(0, true);
        list.setOnItemClickListener((p, view, pos, id) -> picked[0] = pos);
        android.widget.CheckBox withOrder = box.findViewById(R.id.cb_mods_copy_order);
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_RimDroid_ButtonDialog)
                .setTitle(getString(R.string.mods_copy_title, from.getName()))
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mods_copy_next, (d, w) -> chooseModsToCopy(from, targets.get(picked[0]), withOrder.isChecked()))
                .show();
    }

    /**
     * Ticks for each mod of the source (as in Zomdroid): all ticked, except those the target already
     * has (copying them would change nothing, they stay as they are), which are marked as such.
     */
    private void chooseModsToCopy(GameInstance from, GameInstance to, boolean withOrder) {
        List<ModList.Mod> mods = com.rimdroid.mods.ModCopier.listCopyable(new File(from.getGamePath()));
        if (mods.isEmpty()) {
            Toast.makeText(requireContext(), R.string.mods_copy_nothing, Toast.LENGTH_LONG).show();
            return;
        }
        Set<String> there = com.rimdroid.mods.ModCopier.installedIds(new File(to.getGamePath()));
        String[] labels = new String[mods.size()];
        boolean[] checked = new boolean[mods.size()];
        for (int i = 0; i < labels.length; i++) {
            boolean has = there.contains(mods.get(i).packageId);
            labels[i] = has ? getString(R.string.mods_copy_already_there, mods.get(i).name) : mods.get(i).name;
            checked[i] = !has;
        }
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_RimDroid_ButtonDialog)
                .setTitle(getString(R.string.mods_copy_pick_title, to.getName()))
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                    checked[which] = isChecked;
                    updateSelectAllButton((androidx.appcompat.app.AlertDialog) d, checked);
                })
                // Set up below: the default handler would close the dialog.
                .setNeutralButton(R.string.mods_copy_uncheck_all, null)
                .setPositiveButton(R.string.mods_copy_go, (d, w) -> {
                    List<String> picked = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) picked.add(mods.get(i).packageId);
                    // Nothing ticked is fine when only the load order is wanted.
                    if (picked.isEmpty() && !withOrder) {
                        Toast.makeText(requireContext(), R.string.mods_copy_none_picked, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    copyMods(from, to, picked, withOrder);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        // One button for both ways: "Uncheck all" while everything is ticked, "Check all" otherwise,
        // so picking 3 mods out of 100 is not 97 taps.
        updateSelectAllButton(dialog, checked);
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> {
            boolean all = true;
            for (boolean c : checked) all &= c;
            for (int i = 0; i < checked.length; i++) {
                checked[i] = !all;
                dialog.getListView().setItemChecked(i, !all);
            }
            updateSelectAllButton(dialog, checked);
        });
    }

    private void updateSelectAllButton(androidx.appcompat.app.AlertDialog dialog, boolean[] checked) {
        android.widget.Button b = dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL);
        if (b == null) return;
        boolean all = true;
        for (boolean c : checked) all &= c;
        b.setText(all ? R.string.mods_copy_uncheck_all : R.string.mods_copy_check_all);
    }

    private void copyMods(GameInstance from, GameInstance to, List<String> picked, boolean withOrder) {
        if (InstanceShortcuts.isGameRunning(requireContext())) {
            Toast.makeText(requireContext(), R.string.mods_copy_game_running, Toast.LENGTH_LONG).show();
            return;
        }
        android.content.Context app = requireContext().getApplicationContext();
        androidx.appcompat.app.AlertDialog wait = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.mods_copy_running, to.getName()))
                .setMessage("…")
                .setCancelable(false)
                .show();
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            com.rimdroid.mods.ModCopier.Result r = com.rimdroid.mods.ModCopier.copy(
                    new File(from.getGamePath()), new File(to.getGamePath()), picked, withOrder,
                    (name, i, total) -> main.post(() -> wait.setMessage(i + " / " + total + "\n" + name)));
            main.post(() -> {
                wait.dismiss();
                if (!isAdded()) {
                    Toast.makeText(app, app.getString(R.string.mods_copy_done, r.copied.size(), r.alreadyThere.size()),
                            Toast.LENGTH_LONG).show();
                    return;
                }
                StringBuilder sb = new StringBuilder(getString(R.string.mods_copy_done, r.copied.size(), r.alreadyThere.size()));
                if (withOrder) sb.append("\n\n").append(getString(r.orderWritten ? R.string.mods_copy_order_done : R.string.mods_copy_order_failed));
                if (!r.failed.isEmpty()) {
                    sb.append("\n\n").append(getString(R.string.mods_copy_failed));
                    for (String name : r.failed) sb.append("\n• ").append(name);
                }
                new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_RimDroid_ButtonDialog)
                        .setTitle(getString(R.string.mods_copy_running, to.getName()))
                        .setMessage(sb.toString())
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }, "rd-mods-copy").start();
    }

    private void disableAllShown() {
        GameInstance gi = current();
        if (gi == null || !editable) return;
        List<String> ids = new ArrayList<>();
        for (Row r : shown(true)) if (r.mod == null || !r.mod.official) ids.add(r.packageId);
        if (ids.isEmpty()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.mods_disable_all_confirm, ids.size()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mods_disable_all, (d, w) -> {
                    File dir = new File(gi.getGamePath());
                    List<String> order = ModList.activeOrder(dir);
                    if (order == null) return;
                    order.removeAll(ids);
                    if (!ModList.writeOrder(dir, order))
                        Toast.makeText(requireContext(), R.string.mods_switch_failed, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .show();
    }

    // ---- load order ----

    private void sort() {
        GameInstance gi = current();
        if (gi == null) return;
        File dir = new File(gi.getGamePath());
        List<String> order = ModList.activeOrder(dir);
        if (order == null) return;
        List<ModList.Mod> mods = ModList.installed(dir);
        Map<String, ModList.Mod> byId = ModList.byId(mods);
        com.rimdroid.mods.ModSorter.Result r = com.rimdroid.mods.ModSorter.sort(order, byId,
                com.rimdroid.mods.CommunityRules.load(requireContext()));

        StringBuilder sb = new StringBuilder();
        sb.append(r.moved == 0 ? getString(R.string.mods_sort_nochange) : getString(R.string.mods_sort_moved, r.moved));
        if (!r.missing.isEmpty()) {
            sb.append("\n\n").append(getString(R.string.mods_sort_missing));
            for (ModList.Dependency d : r.missing)
                sb.append("\n• ").append(d.displayName.isEmpty() ? d.packageId : d.displayName)
                  .append(d.workshopId.isEmpty() ? "" : "  (" + d.workshopId + ")");
        }
        if (!r.inactive.isEmpty()) {
            sb.append("\n\n").append(getString(R.string.mods_sort_inactive));
            for (ModList.Mod m : r.inactive) sb.append("\n• ").append(m.name);
        }
        if (!r.incompatible.isEmpty()) {
            sb.append("\n\n").append(getString(R.string.mods_sort_incompatible));
            for (String s : r.incompatible) sb.append("\n• ").append(s);
        }
        if (!r.conflicts.isEmpty()) {
            sb.append("\n\n").append(getString(R.string.mods_sort_conflicts));
            for (String id : r.conflicts) {
                ModList.Mod m = byId.get(id);
                sb.append("\n• ").append(m != null ? m.name : id);
            }
        }
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.mods_sort_title)
                .setMessage(sb.toString());
        if (r.moved > 0) {
            b.setNegativeButton(android.R.string.cancel, null)
             .setPositiveButton(R.string.mods_sort_apply, (d, w) -> {
                 if (!ModList.writeOrder(dir, r.order))
                     Toast.makeText(requireContext(), R.string.mods_switch_failed, Toast.LENGTH_SHORT).show();
                 refresh();
             });
        } else {
            b.setPositiveButton(android.R.string.ok, null);
        }
        b.show();
    }

    private boolean draggable(int pos) {
        if (pos < 0 || pos >= rows.size() || !(rows.get(pos) instanceof Row)) return false;
        // Only the whole list: with a search filter the rows on screen are not the whole order.
        return editable && ((Row) rows.get(pos)).active && etSearch.getText().length() == 0;
    }

    private final androidx.recyclerview.widget.ItemTouchHelper.Callback dragCallback =
            new androidx.recyclerview.widget.ItemTouchHelper.Callback() {
        @Override public boolean isLongPressDragEnabled() { return false; }   // the handle starts it
        @Override public boolean isItemViewSwipeEnabled() { return false; }

        @Override
        public int getMovementFlags(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder h) {
            return draggable(h.getAdapterPosition())
                    ? makeMovementFlags(androidx.recyclerview.widget.ItemTouchHelper.UP | androidx.recyclerview.widget.ItemTouchHelper.DOWN, 0) : 0;
        }

        @Override
        public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {
            int a = from.getAdapterPosition(), b = to.getAdapterPosition();
            if (!draggable(a) || !draggable(b)) return false;   // stays inside "Active"
            rows.add(b, rows.remove(a));
            adapter.notifyItemMoved(a, b);
            dragged = true;
            return true;
        }

        @Override public void onSwiped(@NonNull RecyclerView.ViewHolder h, int direction) {}

        @Override
        public void clearView(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder h) {
            super.clearView(r, h);
            if (!dragged) return;
            dragged = false;
            saveDraggedOrder();
        }
    };

    /** The active rows as they now stand on screen become the load order; RimDroid's own mod (not
     *  listed) keeps its index. */
    private void saveDraggedOrder() {
        GameInstance gi = current();
        if (gi == null) return;
        File dir = new File(gi.getGamePath());
        List<String> old = ModList.activeOrder(dir);
        if (old == null) return;
        List<String> order = new ArrayList<>();
        for (Object o : rows) if (o instanceof Row && ((Row) o).active) order.add(((Row) o).packageId);
        int builtin = old.indexOf(ModList.BUILTIN);
        if (builtin >= 0) order.add(Math.min(builtin, order.size()), ModList.BUILTIN);
        if (!ModList.writeOrder(dir, order))
            Toast.makeText(requireContext(), R.string.mods_switch_failed, Toast.LENGTH_SHORT).show();
        rv.post(this::refresh);   // after the drop animation
    }

    /** Long press on an active mod: up / down / to the top / to the bottom of the load order. */
    private void showMoveMenu(View anchor, Row r) {
        GameInstance gi = current();
        if (gi == null || !editable || !r.active) return;
        File dir = new File(gi.getGamePath());
        android.widget.PopupMenu pm = new android.widget.PopupMenu(requireContext(), anchor);
        pm.getMenu().add(0, 1, 0, R.string.mods_move_top);
        pm.getMenu().add(0, 2, 1, R.string.mods_move_up);
        pm.getMenu().add(0, 3, 2, R.string.mods_move_down);
        pm.getMenu().add(0, 4, 3, R.string.mods_move_bottom);
        pm.setOnMenuItemClickListener(item -> {
            List<String> order = ModList.activeOrder(dir);
            if (order == null) return true;
            int i = order.indexOf(r.packageId);
            if (i < 0) return true;
            order.remove(i);
            int to;
            switch (item.getItemId()) {
                case 1: to = 0; break;
                case 2: to = Math.max(0, i - 1); break;
                case 3: to = Math.min(order.size(), i + 1); break;
                default: to = order.size(); break;
            }
            order.add(to, r.packageId);
            if (!ModList.writeOrder(dir, order))
                Toast.makeText(requireContext(), R.string.mods_switch_failed, Toast.LENGTH_SHORT).show();
            refresh();
            return true;
        });
        pm.show();
    }

    private void confirmDelete(ModList.Mod m) {
        GameInstance gi = current();
        if (gi == null) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.mod_delete_title, m.name))
                .setMessage(R.string.mod_delete_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mod_delete_confirm, (d, w) -> {
                    if (!ModList.delete(new File(gi.getGamePath()), m))
                        Toast.makeText(requireContext(), R.string.mods_switch_failed, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .show();
    }

    private void showDetails(Row r) {
        GameInstance gi = current();
        StringBuilder sb = new StringBuilder();
        if (r.mod == null) {
            sb.append(getString(R.string.mods_not_installed));
        } else {
            ModList.Mod m = r.mod;
            if (!m.author.isEmpty()) sb.append(getString(R.string.mods_author, m.author)).append('\n');
            sb.append(m.packageId).append('\n');
            if (!m.supportedVersions.isEmpty())
                sb.append(getString(R.string.mods_versions, String.join(", ", m.supportedVersions))).append('\n');
            String gv = gi != null ? ModList.gameVersion(new File(gi.getGamePath())) : "";
            if (!m.supports(gv)) sb.append(getString(R.string.mods_made_for_note, gv)).append('\n');
            sb.append(getString(R.string.mods_folder, m.folder.getName()));
            if (!m.description.isEmpty()) {
                String d = m.description.replaceAll("\\[/?[a-zA-Z]+[^\\]]*\\]", "").trim();
                if (d.length() > 600) d = d.substring(0, 600) + "…";
                sb.append("\n\n").append(d);
            }
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(r.mod != null ? r.mod.name : r.packageId)
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private final RecyclerView.Adapter<RecyclerView.ViewHolder> adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mod, parent, false);
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            View v = h.itemView;
            Object item = rows.get(pos);
            TextView header = v.findViewById(R.id.tv_mod_header);
            View row = v.findViewById(R.id.row_mod);
            if (item instanceof String) {
                header.setVisibility(View.VISIBLE);
                header.setText((String) item);
                row.setVisibility(View.GONE);
                return;
            }
            header.setVisibility(View.GONE);
            row.setVisibility(View.VISIBLE);
            Row r = (Row) item;
            TextView name = v.findViewById(R.id.tv_mod_name), sub = v.findViewById(R.id.tv_mod_sub);
            MaterialSwitch sw = v.findViewById(R.id.sw_mod);
            ImageButton del = v.findViewById(R.id.btn_mod_delete);
            GameInstance gi = current();
            String version = gi != null ? ModList.gameVersion(new File(gi.getGamePath())) : "";

            if (r.mod == null) {
                name.setText(r.packageId);
                sub.setText(R.string.mods_not_installed);
            } else {
                name.setText(r.mod.name);
                // The game versions the author listed (the newest two), the instance's own in green;
                // no warning for the others: most 1.6 mods run on 1.7 too, and Ludeon promised to keep
                // old mods working. Then the author.
                android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder();
                java.util.List<String> vs = r.mod.supportedVersions;
                for (int k = Math.max(0, vs.size() - 2); k < vs.size(); k++) {
                    if (s.length() > 0) s.append(", ");
                    int at = s.length();
                    s.append(vs.get(k));
                    if (vs.get(k).equals(version))
                        s.setSpan(new android.text.style.ForegroundColorSpan(0xFF4CAF50), at, s.length(), 0);
                }
                if (!r.mod.author.isEmpty()) s.append(s.length() > 0 ? " · " : "").append(r.mod.author);
                sub.setText(s);
            }
            sub.setVisibility(sub.getText().length() == 0 ? View.GONE : View.VISIBLE);

            sw.setOnCheckedChangeListener(null);
            sw.setChecked(r.active);
            sw.setEnabled(editable && (r.mod == null || r.mod.canDisable() || !r.active));
            sw.setOnCheckedChangeListener((b, on) -> toggle(r, on));

            boolean deletable = r.mod != null && r.mod.canDelete();
            del.setVisibility(deletable ? View.VISIBLE : View.INVISIBLE);
            del.setEnabled(editable);
            del.setOnClickListener(x -> confirmDelete(r.mod));
            ImageView drag = v.findViewById(R.id.iv_mod_drag);
            boolean canDrag = draggable(pos);
            drag.setVisibility(r.active ? View.VISIBLE : View.GONE);
            drag.setAlpha(canDrag ? 1f : 0.3f);
            drag.setOnTouchListener((x, e) -> {
                if (canDrag && e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) dragHelper.startDrag(h);
                return canDrag;
            });
            row.setOnClickListener(x -> showDetails(r));
            row.setOnLongClickListener(x -> { showMoveMenu(x, r); return true; });
            row.setAlpha(r.mod == null ? 0.6f : 1f);
        }

        @Override public int getItemCount() { return rows.size(); }
    };
}
