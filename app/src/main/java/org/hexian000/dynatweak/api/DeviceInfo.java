package org.hexian000.dynatweak.api;

import android.os.Build;
import android.util.Log;
import org.hexian000.dynatweak.BuildConfig;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hexian000.dynatweak.Dynatweak.LOG_TAG;

interface DeviceNode {
	void generateHtml(StringBuilder out) throws IOException;

	boolean hasAny();
}

/**
 * Created by hexian on 2017/6/18.
 * HTML device info generator
 */
public class DeviceInfo {

	private final CpuStat stat;
	private final List<DeviceNode> nodes;

	public DeviceInfo(Kernel k) {
		nodes = new ArrayList<>();
		DeviceNode soc = new SoC();
		if (soc.hasAny()) {
			nodes.add(soc);
		}
		CPU cpu;
		stat = new CpuStat();
		for (int i = 0; i < k.getCpuCoreCount(); i++) {
			Kernel.CpuCore cpuCore = k.getCpuCore(i);
			cpu = new CPU(cpuCore.getId(), stat);
			nodes.add(cpu);
			Log.i(LOG_TAG, "cpu" + cpuCore.getId() +
					" in cluster " + cpuCore.getCluster() +
					" policy " + cpuCore.getPolicy() + " detected");
		}
		stat.initialize(k.getCpuCoreCount());
		DeviceNode gpu = new GPU();
		if (gpu.hasAny()) {
			nodes.add(gpu);
		}
		nodes.add(stat);
		nodes.add(new Memory());

		final String[] mountPoint = new String[]{"/data"};
		Set<String> devices = new HashSet<>();
		for (String mount : mountPoint) {
			String path = k.getBlockDevice(mount);
			if (path != null) {
				devices.add("/sys" + path.substring(4));
			} else {
				Log.w(LOG_TAG, "mount point not found: " + mount);
			}
		}
		for (String path : devices) {
			if (k.hasNode(path + "/stat")) {
				nodes.add(new Block(path.substring(path.lastIndexOf('/') + 1)));
			}
		}

		if (BuildConfig.FLAVOR.contentEquals("tz")) {
			nodes.add(new Sensors());
		}
	}

	public String getHtml() {
		stat.sample();
		StringBuilder sb = new StringBuilder();
		for (DeviceNode dev : nodes) {
			try {
				if (dev.hasAny()) {
					dev.generateHtml(sb);
					sb.append("<br/>");
				}
			} catch (IOException ex) {
				Log.e(LOG_TAG, "Device info error", ex);
			}
		}
		return sb.toString();
	}

}

class CpuStat implements DeviceNode {

	/*
	- user: normal processes executing in user mode
	- nice: niced processes executing in user mode
	- system: processes executing in kernel mode
	- idle: twiddling thumbs
	- iowait: In a word, iowait stands for waiting for I/O to complete. But there
	  are several problems:
	  1. Cpu will not wait for I/O to complete, iowait is the time that a task is
	     waiting for I/O to complete. When cpu goes into idle state for
	     outstanding task io, another task will be scheduled on this CPU.
	  2. In a multi-core CPU, the task waiting for I/O to complete is not running
	     on any CPU, so the iowait of each CPU is difficult to calculate.
	  3. The value of iowait field in /proc/stat will decrease in certain
	     conditions.
	  So, the iowait is not reliable by reading from /proc/stat.
	- irq: servicing interrupts
	- softirq: servicing softirqs
	- steal: involuntary wait
	- guest: running a normal guest
	- guest_nice: running a niced guest
	*/

	// "cpu user nice system idle iowait irq softirq"
	private final Pattern cpu_all = Pattern.compile(
			"^cpu {2}(\\d+) (\\d+) (\\d+) (\\d+) (\\d+) (\\d+) (\\d+)",
			Pattern.UNIX_LINES | Pattern.MULTILINE);
	private Pattern[] cpu_line;
	private long cpu_iowait[], cpu_idle[], cpu_total[];
	private int count;
	private double freq_all;
	private long last_iowait[], last_idle[], last_total[];
	private long cpu_all_idle, cpu_all_iowait, cpu_all_total;
	private long last_cpu_all_idle, last_cpu_all_iowait, last_cpu_all_total;
	private NodeMonitor stat;

	void initialize(int count) {
		last_iowait = new long[count];
		last_idle = new long[count];
		last_total = new long[count];
		cpu_iowait = new long[count];
		cpu_idle = new long[count];
		cpu_total = new long[count];
		this.count = count;
		cpu_line = new Pattern[count];
		for (int id = 0; id < count; id++) {
			last_iowait[id] = last_idle[id] = last_total[id] = 0;
			cpu_line[id] = Pattern.compile(
					"^cpu" + id + " (\\d+) (\\d+) (\\d+) (\\d+) (\\d+) (\\d+) (\\d+)",
					Pattern.UNIX_LINES | Pattern.MULTILINE);
		}
		try {
			stat = new NodeMonitor("/proc/stat");
		} catch (Throwable ex) {
			Log.e(LOG_TAG, "cannot access /proc/stat", ex);
			try {
				stat = new NodeMonitor("/proc/stat", Kernel.SU);
			} catch (Throwable ex2) {
				Log.e(LOG_TAG, "root cannot access /proc/stat", ex);
			}
		}
	}

	void sample() {
		if (stat == null) {
			return;
		}
		try {
			String data = stat.readAll();
			{
				Matcher m = cpu_all.matcher(data);
				if (m.find()) {
					long idle = Long.parseLong(m.group(4)),
							iowait = Long.parseLong(m.group(5));
					long total = Long.parseLong(m.group(1)) +
							Long.parseLong(m.group(1)) +
							Long.parseLong(m.group(2)) +
							Long.parseLong(m.group(3)) +
							idle + iowait +
							Long.parseLong(m.group(6)) +
							Long.parseLong(m.group(7));
					cpu_all_iowait = iowait - last_cpu_all_iowait;
					cpu_all_idle = idle - last_cpu_all_idle;
					cpu_all_total = total - last_cpu_all_total;
					if (cpu_all_total < 0 || cpu_all_idle < 0 || cpu_all_iowait < 0) {
						cpu_all_total = cpu_all_idle = cpu_all_iowait = 0;
					}
					last_cpu_all_iowait = iowait;
					last_cpu_all_idle = idle;
					last_cpu_all_total = total;
				} else {
					Log.e(LOG_TAG, "/proc/stat no matching \"cpu\" found");
					stat = null;
					return;
				}
			}
			for (int id = 0; id < count; id++) {
				Matcher m = cpu_line[id].matcher(data);
				if (m.find()) {
					long idle = Long.parseLong(m.group(4)),
							iowait = Long.parseLong(m.group(5));
					long total = Long.parseLong(m.group(1)) +
							Long.parseLong(m.group(1)) +
							Long.parseLong(m.group(2)) +
							Long.parseLong(m.group(3)) +
							idle + iowait +
							Long.parseLong(m.group(6)) +
							Long.parseLong(m.group(7));
					cpu_iowait[id] = iowait - last_iowait[id];
					cpu_idle[id] = idle - last_idle[id];
					cpu_total[id] = total - last_total[id];
					if (cpu_total[id] < 0 || cpu_idle[id] < 0 || cpu_iowait[id] < 0) {
						cpu_total[id] = cpu_idle[id] = cpu_iowait[id] = 0;
					}
					last_iowait[id] = iowait;
					last_idle[id] = idle;
					last_total[id] = total;
				} else {
					cpu_iowait[id] = 0;
					cpu_idle[id] = 0;
					cpu_total[id] = 0;
				}
			}
		} catch (IOException e) {
			Log.e(LOG_TAG, "CpuStat", e);
			stat = null;
		}
	}

	double getCoreUtil(int id, double freq) {
		double util;
		if (freq > 0) {
			util = 1.0 - (double) (cpu_idle[id] + cpu_iowait[id]) / cpu_total[id];
			if (util < 0) {
				util = 0;
			} else if (util > 1) {
				util = 1;
			}
		} else {
			util = 0;
		}
		freq_all += freq;
		return util;
	}

	@Override
	public void generateHtml(StringBuilder out) {
		double idle = (double) cpu_all_idle / cpu_all_total;
		if (idle < 0) {
			idle = 0;
		} else if (idle > 1) {
			idle = 1;
		}
		double iowait = (double) cpu_all_iowait / cpu_all_total;
		if (iowait < 0) {
			iowait = 0;
		} else if (iowait > 1) {
			iowait = 1;
		}
		double busy = 1.0 - (idle + iowait);
		if (busy < 0) {
			busy = 0;
		}
		double util = (1.0 - idle) * (freq_all / count);
		out.append("util: ").append((int) (util * 100.0 + 0.5)).
				append("% busy: ").append((int) (busy * 100.0 + 0.5)).
				   append("% iowait: ").append((int) (iowait * 100.0 + 0.5)).append('%');
		freq_all = 0;
	}

	@Override
	public boolean hasAny() {
		return stat != null;
	}
}

class SoC implements DeviceNode {

	private String node_battery_curr, node_battery_volt;
	private boolean hasSocTemp, hasBatteryTemp;

	SoC() {
		Kernel k = Kernel.getInstance();
		node_battery_curr = "/sys/class/power_supply/battery/current_now";
		hasSocTemp = k.hasSocTemperature();
		try {
			k.getSocTemperature();
			hasSocTemp = true;
		} catch (Throwable e) {
			hasSocTemp = false;
		}
		hasBatteryTemp = k.hasBatteryTemperature();
		try {
			k.getBatteryTemperature();
			hasBatteryTemp = true;
		} catch (Throwable e) {
			hasBatteryTemp = false;
		}
		if (k.hasNode(node_battery_curr)) {
			try {
				k.readNode(node_battery_curr);
			} catch (Throwable ignore) {
				node_battery_curr = null;
			}
		}
		if (!(hasBatteryTemp && hasSocTemp && node_battery_curr != null)) {
			node_battery_volt = "/sys/class/power_supply/battery/voltage_now";
			if (k.hasNode(node_battery_volt)) {
				try {
					k.readNode(node_battery_volt);
				} catch (Throwable ignore) {
					node_battery_volt = null;
				}
			}
		} else {
			node_battery_volt = null;
		}
	}

	@Override
	public void generateHtml(StringBuilder out) {
		Kernel k = Kernel.getInstance();
		if (hasSocTemp) {
			out.append("SoC:");
			out.append(k.getSocTemperature());
			out.append("℃ ");
		}
		if (hasBatteryTemp) {
			out.append("Batt:");
			out.append(k.getBatteryTemperature());
			out.append("℃ ");
		}
		if (node_battery_curr != null) {
			try {
				out.append(getMicroAmpere(k.readNode(node_battery_curr)));
				out.append("mA ");
			} catch (Throwable ignore) {
				node_battery_curr = null;
			}
		}
		if (node_battery_volt != null) {
			try {
				out.append(getMicroAmpere(k.readNode(node_battery_volt)));
				out.append("mV ");
			} catch (Throwable ignore) {
				node_battery_curr = null;
			}
		}
	}

	@Override
	public boolean hasAny() {
		return hasSocTemp || hasBatteryTemp ||
				node_battery_curr != null ||
				node_battery_volt != null;
	}

	// Not a good method
	private int getMicroAmpere(String read) {
		int raw = Integer.parseInt(read);
		double divider = 1000.0;
		if (raw >= 10000000 || raw <= -10000000) {
			divider = 1000000.0;
		}
		return (int) (raw / divider);
	}
}

class GPU implements DeviceNode {

	private String gpu_freq, governor;
	private boolean gpu_temp;

	GPU() {
		Kernel k = Kernel.getInstance();
		gpu_freq = "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq";
		if (!k.hasNode(gpu_freq)) {
			gpu_freq = "/sys/class/kgsl/kgsl-3d0/gpuclk";
			if (!k.hasNode(gpu_freq)) {
				gpu_freq = null;
			}
		}
		if (gpu_freq != null) {
			try {
				k.readNode(gpu_freq);
			} catch (Throwable ignore) {
				gpu_freq = null;
			}
		}
		governor = "/sys/class/kgsl/kgsl-3d0/devfreq/governor";
		if (!k.hasNode(governor)) {
			governor = null;
		}
		if (governor != null) {
			try {
				k.readNode(governor);
			} catch (Throwable ignore) {
				governor = null;
			}
		}
		gpu_temp = k.hasGpuTemperature();
		try {
			k.getGpuTemperature();
		} catch (Throwable ignore) {
			gpu_temp = false;
		}
	}

	@Override
	public boolean hasAny() {
		return gpu_freq != null || governor != null || gpu_temp;
	}

	@Override
	public void generateHtml(StringBuilder out) {
		Kernel k = Kernel.getInstance();
		out.append("gpu: ");
		if (k.hasGpuTemperature()) {
			out.append(k.getGpuTemperature());
			out.append("℃ ");
		}
		if (governor != null) {
			try {
				out.append(k.readNode(governor));
				out.append(":");
			} catch (Throwable e) {
				governor = null;
			}
		}
		if (gpu_freq != null) {
			try {
				long rawFreq = Integer.parseInt(k.readNode(gpu_freq));
				for (int i = 0; i < 2; i++) {
					if (rawFreq > 1000) {
						rawFreq /= 1000;
					}
				}
				out.append(rawFreq);
			} catch (Throwable e) {
				gpu_freq = null;
			}
		}
	}
}

class Sensors implements DeviceNode {

	private final List<MaxTempReader> sensors = new ArrayList<>();
	private final int soc_id;

	Sensors() {
		String path;
		Kernel k = Kernel.getInstance();
		soc_id = k.getSocRawID();
		int i = 0;
		while (k.hasNode(path = k.getThermalZone(i))) {
			try {
				sensors.add(new MaxTempReader(path));
			} catch (IOException ignored) {
			}
			i++;
		}
	}

	@Override
	public boolean hasAny() {
		return sensors.size() > 0;
	}

	@Override
	public void generateHtml(StringBuilder out) throws IOException {
		int i = 0;
		for (MaxTempReader reader : sensors) {
			out.append(i);
			out.append(":");
			out.append(reader.read());
			i++;
			if (i % 5 == 0) {
				out.append("<br/>");
			} else {
				out.append(" ");
			}
		}
		if (i % 5 != 0) {
			out.append("<br/>");
		}
		out.append("model:");
		out.append(Build.MODEL);
		out.append(" soc_id:");
		out.append(soc_id);
	}
}

class CPU implements DeviceNode {
	private final int id;
	private final Kernel.CpuCore core;
	private final CpuStat stat;

	private int maxFreq;

	CPU(int id, CpuStat stat) {
		this.id = id;
		this.stat = stat;
		core = Kernel.getInstance().getCpuCore(id);
		try {
			maxFreq = core.getMaxFrequency();
		} catch (Throwable ex) {
			maxFreq = 1;
		}
	}

	public void generateHtml(StringBuilder out) {
		boolean on = core.isOnline();
		if (on) {
			out.append("<font color=\"#00ff00\">");
		} else {
			out.append("<font color=\"#ff0000\">");
		}
		out.append("cpu");
		out.append(id);
		out.append(": ");
		if (core.hasTemperature()) {
			try {
				out.append(core.getTemperature());
				out.append("℃ ");
			} catch (IOException ignore) {
			}
		}
		generateGovernorAndFrequency(on, out);
		out.append("</font>");
	}

	@Override
	public boolean hasAny() {
		return true;
	}

	private void generateGovernorAndFrequency(boolean on, StringBuilder out) {
		if (on) {
			try {
				out.append(core.getGovernor());
			} catch (Throwable e) {
				out.append("unknown");
			}
			int curFreq;
			try {
				curFreq = core.getScalingCurrentFrequency();
				if (maxFreq < curFreq) {
					maxFreq = curFreq;
				}
				out.append(':').append(curFreq / 1000);
			} catch (Throwable e) {
				curFreq = 0;
			}
			if (stat.hasAny()) {
				out.append(' ').
						append((int) (stat.getCoreUtil(id, (double) curFreq / maxFreq) * 100.0 + 0.5)).
						   append('%');
			}
		} else {
			out.append("offline");
		}
	}
}

class Memory implements DeviceNode {
	private final byte[] buf = new byte[2048];
	final private Pattern MemTotal = Pattern.compile("MemTotal:\\s*(\\d+) kB");
	final private Pattern MemActive = Pattern.compile("Active:\\s*(\\d+) kB");
	final private Pattern MemInactive = Pattern.compile("Inactive:\\s*(\\d+) kB");
	final private Pattern MemAvailable = Pattern.compile("MemAvailable:\\s*(\\d+) kB");
	final private Pattern MemFree = Pattern.compile("MemFree:\\s*(\\d+) kB");
	private RandomAccessFile info;

	Memory() {
		try {
			info = new RandomAccessFile("/proc/meminfo", "r");
		} catch (Throwable e) {
			Log.e(LOG_TAG, "can not access /proc/meminfo", e);
		}
	}

	@Override
	public void generateHtml(StringBuilder out) throws IOException {
		try {
			String data;
			{
				info.seek(0);
				int read = info.read(buf);
				data = new String(buf, 0, read);
			}
			long memTotal, memAvail, memActive, memInactive;
			Matcher matcher;
			matcher = MemTotal.matcher(data);
			if (matcher.find()) {
				memTotal = Long.parseLong(matcher.group(1));
			} else {
				throw new Exception("MemTotal pattern mismatch");
			}
			matcher = MemAvailable.matcher(data);
			if (matcher.find()) {
				memAvail = Long.parseLong(matcher.group(1));
			} else {
				matcher = MemFree.matcher(data);
				if (matcher.find()) {
					memAvail = Long.parseLong(matcher.group(1));
				} else {
					throw new Exception("MemAvailable & MemFree pattern mismatch");
				}
			}
			matcher = MemActive.matcher(data);
			if (matcher.find()) {
				memActive = Long.parseLong(matcher.group(1));
			} else {
				throw new Exception("Active pattern mismatch");
			}
			matcher = MemInactive.matcher(data);
			if (matcher.find()) {
				memInactive = Long.parseLong(matcher.group(1));
			} else {
				throw new Exception("Inactive pattern mismatch");
			}
			out.append("mem: ").append(memAvail / 1024).
					append('/').append(memTotal / 1024).
					   append(" a/i: ").append(memActive / 1024).
					   append('/').append(memInactive / 1024);
		} catch (Throwable ex) {
			Log.e(LOG_TAG, "Memory.generateHtml", ex);
			info = null;
			throw new IOException(ex);
		}
	}

	@Override
	public boolean hasAny() {
		return info != null;
	}
}

class Block implements DeviceNode {
	private static final Pattern statPattern = Pattern.compile(
			"(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)");
	private final String block;
	private NodeMonitor node;
	private long[] last_value, value;
	private long last_sample, sample;

	/*
	 * Name            units         description
	 * ----            -----         -----------
	 * read I/Os       requests      number of read I/Os processed
	 * read merges     requests      number of read I/Os merged with in-queue I/O
	 * read sectors    sectors       number of sectors read
	 * read ticks      milliseconds  total wait time for read requests
	 * write I/Os      requests      number of write I/Os processed
	 * write merges    requests      number of write I/Os merged with in-queue I/O
	 * write sectors   sectors       number of sectors written
	 * write ticks     milliseconds  total wait time for write requests
	 * in_flight       requests      number of I/Os currently in flight
	 * io_ticks        milliseconds  total time this block device has been active
	 * time_in_queue   milliseconds  total wait time for all requests
	 */

	Block(String block) {
		this.block = block;
		last_value = new long[11];
		value = new long[11];
		try {
			node = new NodeMonitor("/sys/block/" + block + "/stat");
			sample();
			swap();
		} catch (IOException e) {
			Log.e(LOG_TAG, "block monitor for " + block, e);
			try {
				node = new NodeMonitor("/sys/block/" + block + "/stat", Kernel.SU);
				sample();
				swap();
			} catch (IOException e2) {
				Log.e(LOG_TAG, "root block monitor for " + block, e2);
				node = null;
			}
		}
	}

	@Override
	public void generateHtml(StringBuilder out) throws IOException {
		sample();
		out.append(block).append(" R:").append(value[0] - last_value[0])
		   .append("iops ").append(value[3] - last_value[3]).append("ms")
		   .append(" W:").append(value[4] - last_value[4])
		   .append("iops ").append((value[9] - last_value[9]) * 100 / (sample - last_sample))
		   .append('%');
		swap();
	}

	private void sample() throws IOException {
		Matcher m = statPattern.matcher(node.readLine());
		if (!m.find()) {
			node = null;
			throw new IOException("block stat pattern mismatch");
		}
		sample = System.currentTimeMillis();
		for (int i = 0; i < 11; i++) {
			value[i] = Long.parseLong(m.group(i + 1));
		}
	}

	private void swap() {
		long[] t = value;
		value = last_value;
		last_value = t;
		long s = sample;
		sample = last_sample;
		last_sample = s;
	}

	@Override
	public boolean hasAny() {
		return node != null;
	}
}
