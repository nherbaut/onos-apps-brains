package fr.pantheosorbonne.cri;

import static org.slf4j.LoggerFactory.getLogger;

import java.io.FileWriter;
import java.io.IOException;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.onosproject.net.Device;
import org.onosproject.net.Device.Type;
import org.onosproject.net.DeviceId;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.EthCriterion;
import org.onosproject.net.flow.instructions.Instructions.OutputInstruction;
import org.onosproject.net.intent.HostToHostIntent;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.topology.TopologyGraph;
import org.onosproject.net.topology.TopologyService;
import org.slf4j.Logger;

import com.google.common.base.Strings;

public class HLFacade {

	private FileWriter flowWriter;
	private FileWriter intentWriter;
	private FileWriter debugWriter;

	private FlowRuleService frs;
	private DeviceService deviceService;
	private IntentService intentService;
	private TopologyService topoService;;

	public static HLFacadeBuilder getDefaultBuilder() {
		return new HLFacadeBuilder();
	}

	static public class HLFacadeBuilder {
		private HLFacade facade;

		public HLFacadeBuilder() {
			facade = new HLFacade();
		}

		public HLFacadeBuilder withFlowRuleSerice(FlowRuleService service) {
			this.facade.frs = service;
			return this;
		}

		public HLFacadeBuilder withDeviceService(DeviceService service) {
			this.facade.deviceService = service;
			return this;
		}

		public HLFacadeBuilder withIntentService(IntentService intentService) {
			this.facade.intentService = intentService;
			return this;
		}

		public HLFacadeBuilder withTopologyService(TopologyService service) {
			this.facade.topoService = service;
			return this;
		}

		public HLFacade build() {
			if (this.facade.topoService == null || this.facade.deviceService == null
					|| (this.facade.intentService == null && this.facade.frs == null)) {
				throw new RuntimeException(
						"Failed to build HLFacade, you should have 1 Device service and 1 intent service or flow service");
			}
			return this.facade;
		}

	}

	private static final Logger log = getLogger(HLFacade.class);

	private HLFacade() {
		try {
			flowWriter = new FileWriter("/home/nherbaut/flow-logs", true);
			intentWriter = new FileWriter("/home/nherbaut/intent-logs", true);
			debugWriter = new FileWriter("/home/nherbaut/debug-logs", true);

		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public void dump() {
		for (Device d : deviceService.getDevices(Type.SWITCH)) {
			for (FlowEntry fe : frs.getFlowEntries(d.id())) {
				writeFlow(fe);
			}

		}
		if (intentService != null) {
			for (Intent intent : intentService.getIntents()) {
				writeIntent(intent);
			}
		}

	}

	private static String citerionTypeToStr(Criterion.Type type) {
		if (type.equals(Criterion.Type.ETH_DST)) {
			return "to:";
		} else if (type.equals(Criterion.Type.ETH_SRC)) {
			return "from:";
		} else {
			return "na:";
		}
	}

	private void writeDebug(String s) {
		try {
			debugWriter.write(s+"\n");
			debugWriter.flush();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	public void writeFlow(FlowRule rule) {
		TopologyGraph graph = topoService.getGraph(topoService.currentTopology());

		StringBuilder builder = new StringBuilder();
		try {

			String matches = rule.selector().criteria().stream()//
					.filter(c -> c.getClass().equals(EthCriterion.class))//
					.map(c -> (EthCriterion) c)//
					.map(c -> citerionTypeToStr(c.type()) + c.mac())//
					.collect(Collectors.joining(","));//
			if (Strings.isNullOrEmpty(matches)) {
				return;
			}

			Function<OutputInstruction, DeviceId> p = o -> graph.getVertexes().stream()//
					.peek(v -> writeDebug("available vertex:" + v.toString()))
					.filter(v -> v.deviceId().equals(rule.deviceId()))//
					.peek(v -> writeDebug("matching vertex with device id" + v.toString()))
					.map(v -> graph.getEdgesFrom(v).stream()//
							.peek( e -> writeDebug("ports from edge " + e.link().src().port().toString()))
							.filter(e -> e.link().src().port().equals(o.port()))//
							.peek( e -> writeDebug("matching ports from edge " + e.link().src().port().toString()))
							.map( e -> e.dst().deviceId())
							.peek(d -> writeDebug("matching next device " + d))
							.findFirst().orElseThrow())//
					.findFirst()//
					.orElseThrow();

			writeDebug(rule.treatment().allInstructions().stream().map(i -> i.type().toString()).collect(Collectors.toSet()).stream()
					.collect(Collectors.joining(","))+"\n");
			
			String sendTo = rule.treatment().immediate().stream()//
					.filter(t -> t instanceof OutputInstruction)//
					.map(t -> (OutputInstruction) t).map(p).map(d -> d.toString()).collect(Collectors.joining(","));
			
			if(Strings.isNullOrEmpty(sendTo)) {
				sendTo="DROP";
			}else {
				sendTo="OUTPUT:"+sendTo;
			}

			builder.append(System.currentTimeMillis());
			builder.append("\t");
			builder.append(rule.deviceId().toString());
			builder.append("\t");
			builder.append(matches);
			builder.append("\t");
			builder.append(sendTo);
			builder.append("\n");
			flowWriter.append(builder.toString());
			flowWriter.flush();
		} catch (IOException | NoSuchElementException e) {
			log.warn("failed to write log", e);
		}
	}

	public void writeIntent(Intent intent) {
		try {
			StringBuilder builder = new StringBuilder();

			if (intent instanceof HostToHostIntent) {
				var h2h = (HostToHostIntent) intent;
				builder.append(System.currentTimeMillis());
				builder.append(intent.appId().name()).append("\t");
				builder.append(intentService.getIntentState(intent.key())).append("\t");
				builder.append(h2h.resources().stream().map(Object::toString).collect(Collectors.joining("\t")));
				intentWriter.write(builder.append("\n").toString());
			}

		} catch (IOException e) {
			log.error("failed to write log", e);
		}
	}
}