package com.rimdroid;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.WindowInsets;
import android.graphics.Insets;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;
import androidx.activity.EdgeToEdge;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.rimdroid.databinding.ActivityLauncherBinding;

public class LauncherActivity extends AppCompatActivity {

    private ActivityLauncherBinding binding;
    private NavController navController;
    private AppBarConfiguration appBarConfiguration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);

        binding = ActivityLauncherBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.appbarLayout.setOnApplyWindowInsetsListener((v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), insets.top, v.getPaddingRight(), insets.bottom);
            return windowInsets;
        });

        setSupportActionBar(binding.appbar);

        appBarConfiguration = new AppBarConfiguration.Builder(R.id.launcher_fragment)
                .setOpenableLayout(binding.drawerLayout)
                .build();

        navController = Navigation.findNavController(this, R.id.nav_host_fragment);
        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
        NavigationUI.setupWithNavController(binding.launcherNv, navController);

        binding.launcherNv.setNavigationItemSelectedListener(item -> {
            binding.drawerLayout.close();
            int id = item.getItemId();
            if (id == R.id.action_settings) {
                navController.navigate(R.id.action_settings);
                return true;
            } else if (id == R.id.action_install_instance) {
                navController.navigate(R.id.action_install_instance);
                return true;
            } else if (id == R.id.action_manage_storage) {
                Uri rootUri = DocumentsContract.buildRootsUri(C.STORAGE_PROVIDER_AUTHORITY);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(rootUri, "vnd.android.document/root");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return true;
            }
            return NavigationUI.onNavDestinationSelected(item, navController)
                    || super.onOptionsItemSelected(item);
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }
}
