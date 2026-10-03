package com.example.remotestatus.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.remotestatus.BuildConfig
import com.example.remotestatus.R
import com.example.remotestatus.core.Bytes
import com.example.remotestatus.core.Gauges
import com.example.remotestatus.core.Time
import com.example.remotestatus.data.DashboardState
import com.example.remotestatus.data.Device
import com.example.remotestatus.data.Relay
import com.example.remotestatus.data.RelayClient
import com.example.remotestatus.data.RelayId
import com.example.remotestatus.data.Repository
import com.example.remotestatus.data.displayName
import com.example.remotestatus.data.formatBytes
import com.example.remotestatus.databinding.ActivityDashboardBinding
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class DashboardActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDashboardBinding
    private lateinit var repository: Repository

    /** Row views are reused across the 1 s refresh so the list never flickers or rebuilds. */
    private class ServerRow(
        val container: LinearLayout,
        val name: TextView,
        val metrics: TextView
    )

    private val rows = mutableMapOf<String, ServerRow>()

    /** Session peak of the total bandwidth, used as the scale of the bandwidth gauge. */
    private var bandwidthPeak = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = Repository(RelayClient(RELAYS, APP_TOKEN), RELAYS)

        binding.refreshButton.setOnClickListener { refreshNow() }
        binding.serverList.removeAllViews()
        observeDashboard()
    }

    private fun observeDashboard() {
        lifecycleScope.launch {
            repository.dashboard()
                .catch { error ->
                    binding.connectionStatus.text = getString(R.string.refresh_failed, error.message ?: "未知错误")
                }
                .collect { state -> render(state) }
        }
    }

    private fun refreshNow() {
        lifecycleScope.launch {
            runCatching { repository.loadDashboard() }
                .onSuccess { render(it) }
                .onFailure { binding.connectionStatus.text = getString(R.string.refresh_failed, it.message ?: "未知错误") }
        }
    }

    private fun render(state: DashboardState) {
        val totals = state.totals
        binding.totalCount.setTextIfChanged(getString(R.string.total_count, totals.totalDevices, totals.onlineDevices))
        binding.connectionStatus.setTextIfChanged(state.relayStates.entries.joinToString("  ·  ") { "${it.key.displayName} ${it.value}" })
        binding.updatedAt.setTextIfChanged(getString(R.string.updated_at, Time.localFromMillis(state.updatedAtMillis)))

        binding.cpuCores.setTextIfChanged(getString(R.string.core_count, totals.totalCores))
        binding.cpuUsage.setTextIfChanged(
            getString(
                R.string.cpu_usage,
                Bytes.percent(totals.cpuPercent),
                Bytes.compactNumber(totals.usedCores)
            )
        )
        binding.memoryUsage.setTextIfChanged("${formatBytes(totals.usedMemoryBytes)} / ${formatBytes(totals.totalMemoryBytes)}")
        binding.memoryPercent.setTextIfChanged(getString(R.string.memory_usage, Bytes.percent(totals.memoryPercent)))
        binding.networkSpeed.setTextIfChanged(
            getString(
                R.string.network_usage,
                Bytes.rate(totals.downloadBytesPerSecond),
                Bytes.rate(totals.uploadBytesPerSecond)
            )
        )
        binding.bandwidth.setTextIfChanged(Bytes.rate(totals.bandwidthBytesPerSecond))
        binding.bandwidthCumulative.setTextIfChanged(formatBytes(totals.cumulativeBytes))

        // Total-resource gauges: CPU, memory, and total bandwidth consumption.
        Gauges.apply(binding.cpuBar, totals.cpuPercent)
        Gauges.apply(binding.memoryBar, totals.memoryPercent)

        bandwidthPeak = maxOf(bandwidthPeak, totals.bandwidthBytesPerSecond)
        val bandwidthPercent = if (bandwidthPeak > 0.0) {
            totals.bandwidthBytesPerSecond / bandwidthPeak * 100.0
        } else {
            0.0
        }
        Gauges.apply(binding.bandwidthBar, bandwidthPercent, fixedColorResource = R.color.primary)
        binding.bandwidthPeak.setTextIfChanged(getString(R.string.bandwidth_peak, Bytes.rate(bandwidthPeak)))

        binding.emptyState.visibility = if (state.devices.isEmpty()) View.VISIBLE else View.GONE
        binding.serverList.visibility = if (state.devices.isEmpty()) View.GONE else View.VISIBLE
        syncRows(state.devices)
    }

    private fun syncRows(devices: List<Device>) {
        val stale = rows.keys.toMutableSet()

        devices.forEachIndexed { index, device ->
            val row = rows.getOrPut(device.id) { createRow(device) }
            row.name.setTextIfChanged(displayName(device))
            row.metrics.setTextIfChanged(
                getString(
                    R.string.server_metrics,
                    device.relay.id.displayName,
                    Bytes.percent(device.summary.cpuPercent),
                    Bytes.percent(device.summary.memoryPercent)
                )
            )
            if (binding.serverList.indexOfChild(row.container) != index) {
                binding.serverList.removeView(row.container)
                binding.serverList.addView(row.container, index)
            }
            stale.remove(device.id)
        }

        stale.forEach { id ->
            rows.remove(id)?.let { binding.serverList.removeView(it.container) }
        }
    }

    private fun createRow(device: Device): ServerRow {
        val padding = resources.getDimensionPixelSize(R.dimen.panel_padding)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.server_button_background)
            setPadding(padding, padding, padding, padding)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = resources.getDimensionPixelSize(R.dimen.card_gap)
            layoutParams = params
            isClickable = true
            isFocusable = true
            setOnClickListener { openDetail(device) }
            setOnLongClickListener {
                promptRename(device)
                true
            }
        }
        val name = TextView(this).apply {
            setTextColor(getColor(R.color.text_primary))
            textSize = 17f
            // Relay names can be long (address + tag); keep the card compact.
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val metrics = TextView(this).apply {
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
        }
        container.addView(name)
        container.addView(metrics)
        return ServerRow(container, name, metrics)
    }

    private fun openDetail(device: Device) {
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra(DetailActivity.EXTRA_RELAY, device.relay.id.name)
            putExtra(DetailActivity.EXTRA_DEVICE_ID, device.summary.deviceId)
            putExtra(DetailActivity.EXTRA_NAME, device.summary.name)
            putExtra(DetailActivity.EXTRA_HOSTNAME, device.summary.hostname)
            putExtra(DetailActivity.EXTRA_ADDRESS, device.summary.address)
            putExtra(DetailActivity.EXTRA_PORT, device.summary.port ?: 0)
            putExtra(DetailActivity.EXTRA_CPU, device.summary.cpuPercent)
            putExtra(DetailActivity.EXTRA_MEMORY, device.summary.memoryPercent)
        }
        startActivity(intent)
    }

    /** Renaming writes to the relay that owns the device, so the name survives app restarts. */
    private fun promptRename(device: Device) {
        val input = EditText(this).apply {
            setText(displayName(device))
            setSelection(text.length)
            hint = getString(R.string.rename_title)
            maxLines = 3
        }
        val wrapper = FrameLayout(this).apply {
            val padding = resources.getDimensionPixelSize(R.dimen.screen_padding)
            setPadding(padding, padding / 2, padding, 0)
            addView(
                input,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            )
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(wrapper)
            .setPositiveButton(R.string.save) { _, _ ->
                submitRename(device, input.text.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun submitRename(device: Device, name: String) {
        lifecycleScope.launch {
            runCatching { repository.rename(device, name) }
                .onSuccess {
                    Toast.makeText(this@DashboardActivity, R.string.rename_saved, Toast.LENGTH_SHORT).show()
                    refreshNow()
                }
                .onFailure {
                    Toast.makeText(
                        this@DashboardActivity,
                        getString(R.string.rename_failed, it.message ?: "未知错误"),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    companion object {
        val APP_TOKEN = BuildConfig.APP_TOKEN

        val RELAYS = listOf(
            Relay(RelayId.V4, BuildConfig.RELAY_V4_HOST, BuildConfig.RELAY_V4_PORT, BuildConfig.RELAY_V4_SCHEME),
            Relay(RelayId.V6, BuildConfig.RELAY_V6_HOST, BuildConfig.RELAY_V6_PORT, BuildConfig.RELAY_V6_SCHEME)
        )
    }
}
