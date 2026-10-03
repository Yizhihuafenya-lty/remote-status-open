package com.example.remotestatus.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.remotestatus.R
import com.example.remotestatus.core.Bytes
import com.example.remotestatus.core.Gauges
import com.example.remotestatus.data.Device
import com.example.remotestatus.data.DeviceSummary
import com.example.remotestatus.data.NetworkRate
import com.example.remotestatus.data.NetworkRateTracker
import com.example.remotestatus.data.RelayClient
import com.example.remotestatus.data.RelayId
import com.example.remotestatus.data.Repository
import com.example.remotestatus.data.basicConfigurationLines
import com.example.remotestatus.data.displayName
import com.example.remotestatus.data.formatBytes
import com.example.remotestatus.data.networkSpeed
import com.example.remotestatus.databinding.ActivityDetailBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DetailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDetailBinding
    private lateinit var repository: Repository
    private lateinit var device: Device

    private val rateTracker = NetworkRateTracker()

    /** Live blocks are created once; only text and gauge values change on each refresh. */
    private class LiveBlock(val value: TextView, val bar: ProgressBar?)

    private val liveBlocks = mutableMapOf<String, LiveBlock>()

    /** Key/value rows are created once and only their text changes on each refresh. */
    private val configurationRows = mutableMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val relayId = intent.getStringExtra(EXTRA_RELAY)?.let { RelayId.valueOf(it) } ?: RelayId.V4
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID).orEmpty()
        val summary = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val hostname = intent.getStringExtra(EXTRA_HOSTNAME).orEmpty()
        val address = intent.getStringExtra(EXTRA_ADDRESS)
        val port = intent.getIntExtra(EXTRA_PORT, 0).takeIf { it > 0 }
        val cpuPercent = intent.getDoubleExtra(EXTRA_CPU, 0.0)
        val memoryPercent = intent.getDoubleExtra(EXTRA_MEMORY, 0.0)

        val relay = DashboardActivity.RELAYS.firstOrNull { it.id == relayId } ?: DashboardActivity.RELAYS.first()
        device = Device(
            relay,
            DeviceSummary(
                deviceId = deviceId,
                hostname = hostname,
                name = summary.ifBlank { hostname },
                address = address,
                port = port,
                collectedAt = null,
                online = true,
                cpuPercent = cpuPercent,
                memoryPercent = memoryPercent
            )
        )

        repository = Repository(RelayClient(DashboardActivity.RELAYS, DashboardActivity.APP_TOKEN), DashboardActivity.RELAYS)

        binding.backButton.setOnClickListener { finish() }
        binding.renameButton.setOnClickListener { promptRename() }

        // Long relay names must not push the rest of the page off screen.
        binding.title.maxLines = 3
        binding.title.ellipsize = android.text.TextUtils.TruncateAt.END

        binding.title.text = displayName(device)
        binding.relayValue.text = "${relay.id.displayName} · ${repository.endpoint(relay)}"

        lifecycleScope.launch {
            while (true) {
                load()
                delay(Repository.REFRESH_MILLIS)
            }
        }
    }

    private suspend fun load() {
        runCatching { repository.loadMetric(device) }
            .onSuccess { metric ->
                binding.title.text = displayName(device)
                binding.status.text = getString(
                    R.string.online_status,
                    Bytes.percent(metric.snapshot.cpu.usagePercent),
                    Bytes.percent(metric.snapshot.memory.usedPercent)
                )

                basicConfigurationLines(device, metric.snapshot).forEach { (label, value) ->
                    row(configurationRows, binding.configurationContainer, label).text = value
                }

                val snapshot = metric.snapshot
                liveBlock(LIVE_CPU, withBar = true).let { block ->
                    block.value.text = getString(
                        R.string.live_cpu,
                        snapshot.cpu.coreCount,
                        Bytes.percent(snapshot.cpu.usagePercent)
                    )
                    block.bar?.let { Gauges.apply(it, snapshot.cpu.usagePercent) }
                }

                val memory = snapshot.memory
                liveBlock(LIVE_MEMORY, withBar = true).let { block ->
                    block.value.text = getString(
                        R.string.live_memory,
                        formatBytes(memory.totalBytes - memory.availableBytes),
                        formatBytes(memory.totalBytes),
                        Bytes.percent(memory.usedPercent)
                    )
                    block.bar?.let { Gauges.apply(it, memory.usedPercent) }
                }

                val root = snapshot.filesystems
                    .filter { it.mount == "/" }
                    .maxByOrNull { it.totalBytes }
                liveBlock(LIVE_DISK, withBar = true).let { block ->
                    block.value.text = root?.let {
                        getString(R.string.disk_usage, formatBytes(it.usedBytes)) +
                            " / ${formatBytes(it.totalBytes)} · ${Bytes.percent(it.usedPercent)}"
                    } ?: "—"
                    block.bar?.let { Gauges.apply(it, root?.usedPercent ?: 0.0) }
                }

                val rate = rateTracker.rate(device.id, snapshot, System.currentTimeMillis())
                liveBlock(LIVE_NETWORK, withBar = false).value.text = networkSpeed(snapshot, rate)
            }
            .onFailure {
                binding.status.text = getString(R.string.read_failed, it.message ?: "未知错误")
            }
    }

    /** Live metric block: a label row with its value plus an animated usage gauge. */
    private fun liveBlock(label: String, withBar: Boolean): LiveBlock {
        liveBlocks[label]?.let { return it }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = resources.getDimensionPixelSize(R.dimen.card_gap)
            layoutParams = params
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labelView = TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueView = TextView(this).apply {
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }
        header.addView(labelView)
        header.addView(valueView)
        container.addView(header)

        val bar = if (withBar) {
            ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1_000
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.gauge_height)
                ).apply { topMargin = resources.getDimensionPixelSize(R.dimen.gauge_gap) }
            }.also { container.addView(it) }
        } else {
            null
        }

        binding.liveContainer.addView(container)
        return LiveBlock(valueView, bar).also { liveBlocks[label] = it }
    }

    private fun row(store: MutableMap<String, TextView>, parent: LinearLayout, label: String): TextView {
        store[label]?.let { return it }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = resources.getDimensionPixelSize(R.dimen.row_gap)
            layoutParams = params
        }
        val labelView = TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valueView = TextView(this).apply {
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }
        container.addView(labelView)
        container.addView(valueView)
        parent.addView(container)
        store[label] = valueView
        return valueView
    }

    private fun promptRename() {
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
                val name = input.text.toString()
                lifecycleScope.launch {
                    runCatching { repository.rename(device, name) }
                        .onSuccess {
                            device = device.copy(summary = device.summary.copy(name = name.trim()))
                            binding.title.text = displayName(device)
                            Toast.makeText(this@DetailActivity, R.string.rename_saved, Toast.LENGTH_SHORT).show()
                        }
                        .onFailure {
                            Toast.makeText(
                                this@DetailActivity,
                                getString(R.string.rename_failed, it.message ?: "未知错误"),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val LIVE_CPU = "CPU"
        private const val LIVE_MEMORY = "内存"
        private const val LIVE_DISK = "磁盘"
        private const val LIVE_NETWORK = "网络"

        const val EXTRA_RELAY = "relay"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_NAME = "name"
        const val EXTRA_HOSTNAME = "hostname"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_PORT = "port"
        const val EXTRA_CPU = "cpu"
        const val EXTRA_MEMORY = "memory"
    }
}
