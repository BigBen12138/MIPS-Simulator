/* On my honor, I have neither given nor received unauthorized aid on this assignment */
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;


/**
 * 模拟器
 *
 * @author Xie Lian
 * @since 1.0.0
 */
public class MIPSsim {

	private int address;

	private List<Instruction> instructions;

	private List<Instruction> data;

	public MIPSsim(Integer beginAddress) {
		this.address = beginAddress;
		this.instructions = new ArrayList<>();
		this.data = new ArrayList<>();
	}

	public String disassemble(List<String> binary) {
		this.instructions.clear();
		this.data.clear();
		Assembler.BREAK = false;

		StringBuilder builder = new StringBuilder();
		for (String bin : binary) {
			Instruction instruction = disassemble(bin);
			builder.append(String.format("%s\t%d\t%s", bin, instruction.getAddress(), instruction.getInstruction())).append(System.lineSeparator());
		}

		return builder.toString();
	}

	public Instruction disassemble(String binary) {
		//解析指令操作码
		Instruction instruction = new Instruction(address);
		if (!Assembler.BREAK) {
			//解析指令类别
			Assembler.parseInstruction(binary, instruction);
			instructions.add(instruction);
		} else {
			Assembler.parseData(binary, instruction);
			data.add(instruction);
		}
		if (!Assembler.BREAK) {
			Assembler.BREAK = "BREAK".equals(instruction.getOperation());
		}
		address += 4;
		return instruction;
	}

	public String simulate() {
		if (this.instructions != null) {
			Simulator simulator = new Simulator(instructions, data, 0);
			return simulator.simulate();
		}
		return "";
	}

	/**
	 * 汇编器
	 */
	private static class Assembler {
		private static boolean BREAK = false;

		public static void parseInstruction(String binary, Instruction instruction) {
			//解析指令操作码
			if (!Assembler.BREAK) {
				//解析指令类别
				int category = parseCategory(binary);
				String op = parseOperation(binary, category);
				instruction.setCategory(category);
				instruction.setOperation(op);
				//解析指令操作数
				parseOpNumber(binary, instruction);
			} else {
				parseData(binary, instruction);
			}
			if (!BREAK) {
				Assembler.BREAK = "BREAK".equals(instruction.getOperation());
			}
		}

		public static int parseCategory(String binary) {
			String binCategory = binary.substring(0, 2);
			return Category.getCategory(binCategory);
		}

		private static String parseOperation(String binary, int category) {
			String opCode = binary.substring(2, 6);
			return Category.getOperation(category, Integer.parseInt(opCode, 2));
		}

		private static void parseOpNumber(String binary, Instruction instruction) {
			try {
				//注意立即数和offset都是补码表示！
				String rs = binary.substring(6, 11),
						rt = binary.substring(11, 16),
						rd = binary.substring(16, 21),
						shift = binary.substring(21, 26),
						offset = binary.substring(16),
						operation = instruction.getOperation();
				switch (instruction.getCategory()) {
					case 1 -> {
						switch (operation) {
							case "J" -> {
								//J指令的跳转地址：当前PC的高四位+toIndex<<2
								int index = Integer.parseInt(instruction.getBinAddress().substring(0, 4) + binary.substring(6) + "00", 2);
								instruction.setTarget(index);
								instruction.setInstruction(String.format(Instruction.JUMP, operation, index));
							}
							case "JR" -> {
								instruction.setRs(rs);
								instruction.setInstruction(String.format(Instruction.JUMP, operation, instruction.getRs()));
							}
							case "BEQ" -> {
								//int value = Integer.parseUnsignedInt(offset + "00", 2);
								int value = Util.complement16toInteger(offset) << 2;
								//offset = Util.signedExtend(offset + "00", 32);
								instruction.setRs(rs).setRt(rt).setValue(value);
								instruction.setInstruction(Instruction.TWO_REGISTER_OFFSET, operation, instruction.getRs(), instruction.getRt(), instruction.getValue());
							}
							case "BLTZ", "BGTZ" -> {
								int value = Util.complement16toInteger(offset) << 2;
								//offset = Util.signedExtend(offset + "00", 32);
								instruction.setRs(rs).setValue(value);
								instruction.setInstruction(Instruction.ONE_REGISTER_OFFSET, operation, instruction.getRs(), instruction.getValue());
							}

							case "BREAK" -> instruction.setInstruction("BREAK");

							case "SLL", "SRL", "SRA" -> {
								instruction.setRt(rt).setRd(rd);
								instruction.setShift(shift);
								instruction.setInstruction(String.format(Instruction.CAL_IMMEDIATE, operation, instruction.getRd(), instruction.getRt(), instruction.getShift()));
							}

							case "SW", "LW" -> {
								instruction.setRs(rs).setRt(rt);
								//offset = Util.signedExtend(offset, 32);
								int value = Util.complement16toInteger(offset);
								instruction.setValue(value);
								instruction.setInstruction(Instruction.LOAD_SAVE_WORD, operation, instruction.getRt(), instruction.getValue(), instruction.getRs());
							}
							case "NOP" -> instruction.setInstruction("NOP");

						}
					}
					case 2 -> {
						instruction.setRs(rs).setRt(rt);
						Integer value = Util.complement16toInteger(offset);
						instruction.setValue(value);
						switch (operation) {
							case "ADD", "SUB", "MUL", "AND",
									"OR", "XOR", "NOR", "SLT" -> {
								instruction.setRd(binary.substring(16, 21));
								instruction.setInstruction(Instruction.THREE_REGISTER, operation, instruction.getRd(), instruction.getRs(), instruction.getRt());
							}
							case "ADDI", "ANDI", "ORI", "XORI" -> {
								instruction.setValue(value);
								instruction.setInstruction(Instruction.CAL_IMMEDIATE, operation, instruction.getRt(), instruction.getRs(), instruction.getValue());
							}
						}
					}
				}
			} catch (NumberFormatException e) {
				//System.err.println(instruction.getBinAddress());
				e.printStackTrace();
			}

		}

		public static void parseData(String binary, Instruction instruction) {
			if (binary != null) {
				instruction.setValue(Integer.parseUnsignedInt(binary, 2));
				instruction.setInstruction(String.valueOf(instruction.getValue()));
			}
		}


	}

	/**
	 * 模拟器
	 */
	private static class Simulator {
		private Integer cycle;
		public static final String SEPERATE = "--------------------" + System.lineSeparator();
		public static final String CYCLE_INSTRUCTION = "Cycle:%d\t%d\t%s" + System.lineSeparator() + System.lineSeparator();
		public static final String REGISTERS = "Registers" + System.lineSeparator()
				+ "R00:\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d" + System.lineSeparator()
				+ "R08:\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d" + System.lineSeparator()
				+ "R16:\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d" + System.lineSeparator()
				+ "R24:\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d" + System.lineSeparator()
				+ System.lineSeparator() + "Data" + System.lineSeparator();
		public static final String DATA = "%d:\t%d\t%s\t%d\t%d\t%d\t%d\t%d\t%d" + System.lineSeparator();

		private long PC;

		private List<Instruction> instructions;

		private Integer[] register;

		private Integer dataAddr;

		private Integer[] data;

		public Simulator(List<Instruction> instructions, List<Instruction> data, Integer cycle) {
			if (instructions != null && !instructions.isEmpty()) {
				this.instructions = instructions;
				this.register = new Integer[32];
				Arrays.fill(this.register, 0);
				this.PC = instructions.get(0).getAddress();
			}
			if (data != null && !data.isEmpty()) {
				this.dataAddr = data.get(0).getAddress();
				this.data = new Integer[data.size()];
				Arrays.fill(this.data, 0);
				fillData(data);
			}
			this.cycle = cycle;
		}

		private String pipeline() {
			return "";
		}

		private String simulate() {
			if (instructions != null) {
				StringBuilder builder = new StringBuilder();
				Instruction instruction;
				boolean end = false;
				do {
					builder.append(SEPERATE);
					cycle++;
					//System.out.println("PC: " + PC);
					instruction = instructions.get((int) ((PC - 256) / 4));
					//System.out.println(instruction);
					builder.append(String.format(CYCLE_INSTRUCTION, cycle, instruction.getAddress(), instruction.getInstruction()));
					PC += 4;
					Integer category = instruction.getCategory();
					String operation = instruction.getOperation();
					Integer sa = instruction.getShift(),
							rs = instruction.getRs(),
							rt = instruction.getRt(),
							rd = instruction.getRd(),
							value = instruction.getValue();
					switch (category) {
						case 1 -> {
							switch (operation) {
								case "J" -> this.PC = instruction.getTarget();

								case "JR" -> this.PC = instruction.getRs();

								case "SLL", "SRL", "SRA" -> register[rd] = Category.operationMap.get(operation).operation(register[rt], sa);
									/*switch (operation) {
										case "SLL" -> register[rd] = register[rt] << sa;
										case "SRL" -> register[rd] = register[rt] >>> sa;
										case "SRA" -> register[rd] = register[rt] >> sa;
									}*/

								case "BEQ" -> {
									if (register[rs].equals(register[rt])) {
										PC += Integer.parseUnsignedInt(Util.signedExtend(Util.int2String(value, 18), 32), 2);
									}
								}
								case "BLTZ" -> {
									if (register[rs] < 0) {
										PC += Integer.parseUnsignedInt(Util.signedExtend(Util.int2String(value, 18), 32), 2);
									}
								}
								case "BGTZ" -> {
									if (register[rs] > 0) {
										PC += Integer.parseUnsignedInt(Util.signedExtend(Util.int2String(value, 18), 32), 2);
									}
								}
								case "NOP" -> {

								}
								case "BREAK" -> end = true;

								case "LW" -> {
									int index = Integer.parseUnsignedInt(Util.signedExtend(Util.int16toString(value), 32), 2);
									//System.out.println("LW: " + (value + register[rs] - dataAddr) / 4);
									register[rt] = data[(index + register[rs] - dataAddr) / 4];
								}
								case "SW" -> {
									int index = Integer.parseUnsignedInt(Util.signedExtend(Util.int16toString(value), 32), 2);
									data[(index + register[rs] - dataAddr) / 4] = register[rt];
								}

							}
						}
						case 2 -> {
							switch (operation) {
								case "ADD" -> register[rd] = register[rs] + register[rt];
								case "SUB" -> register[rd] = register[rs] - register[rt];
								case "MUL" -> register[rd] = register[rs] * register[rt];
								case "AND" -> register[rd] = register[rs] & register[rt];
								case "OR" -> register[rd] = register[rs] | register[rt];
								case "XOR" -> register[rd] = register[rs] ^ register[rt];
								case "NOR" -> register[rd] = ~(register[rs] ^ register[rt]);
								case "SLT" -> {
									if (register[rs] < register[rt]) {
										register[rd] = 1;
									} else register[rd] = 0;
								}
								case "ADDI", "ANDI", "ORI", "XORI" -> register[rt] = Category.operationMap.get(operation).operation(register[rs], value);
							}
						}
					}
					builder.append(String.format(REGISTERS, (Object[]) this.register));
					for (int i = 0; i < data.length; i += 8) {
						builder.append(String.format(DATA, dataAddr + i * 4, data[i], data[i + 1], data[i + 2], data[i + 3], data[i + 4], data[i + 5], data[i + 6], data[i + 7]));
					}
					builder.append(System.lineSeparator());
					//System.out.println(builder.toString());
				} while (!end);
				return builder.toString();
			}
			return "";
		}

		/**
		 * 对16位 offset进行符号扩展 先左i位，再符号扩展至32位
		 */
		/*private static int extend(int offset, int i) {
			int flag = (offset >> 15 & 1) == 1 ? -1 : 1;
			int temp = offset & 0x0000FFFF;
			temp = temp << i;
			//System.out.println(Integer.toHexString(temp) + flag);
			if (flag == -1) {
				//需要补14个1
				temp |= 0xFFFC;
			}
			return temp;
		}*/

		private void fillData(List<Instruction> data) {
			for (int i = 0, len = data.size(); i < len; i++) {
				this.data[i] = data.get(i).getValue();
			}
			//System.out.println(Arrays.toString(this.data));
		}

	}

	//逻辑左移：
	//算术右移：正数补0，负数补1
	//逻辑移位:移动符号位
	/// >>> :左边补0 逻辑右移
	// >> :正数补0 负数补0 算术右移
	public static void main(String[] args) {
		MIPSsim simulator = new MIPSsim(256);
		Path sampleInput = Paths.get(args[0]);
		try {
			List<String> sample = Files.readAllLines(sampleInput, Charset.defaultCharset());
			String disassembly = simulator.disassemble(sample);
			Path assembly = Paths.get("./disassembly.txt");
			Files.writeString(assembly, disassembly);
			String simulation = simulator.simulate();
			Path simulated = Paths.get("./simulation.txt");
			Files.writeString(simulated, simulation);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}

/**
 * 指令
 */
class Instruction {
	public static final String JUMP = "%s #%s"; // op value1
	public static final String ONE_REGISTER_OFFSET = "%s R%s, #%s";
	public static final String TWO_REGISTER_OFFSET = "%s R%s, R%s, #%s";
	public static final String LOAD_SAVE_WORD = "%s R%s, %s(R%s)";
	public static final String CAL_IMMEDIATE = "%s R%s, R%s, #%s";
	public static final String THREE_REGISTER = "%s R%s, R%s, R%s";

	private Integer category;
	//地址
	private Integer address;

	private String operation;

	private Integer rs;

	private Integer rt;

	private Integer rd;

	private Integer shift;

	private Integer func;

	private Integer value;

	private Integer target;

	private String instruction;

	public Instruction(Integer address) {
		this.address = address;
	}

	public Integer getCategory() {
		return category;
	}

	public void setCategory(Integer category) {
		this.category = category;
	}

	public Integer getAddress() {
		return address;
	}

	public String getBinAddress() {
		return Util.zeroExtend(Integer.toBinaryString(address), 32);
	}

	public void setAddress(Integer address) {
		this.address = address;
	}

	public String getOperation() {
		return operation;
	}

	public void setOperation(String operation) {
		this.operation = operation;
	}

	public Integer getRs() {
		return rs;
	}

	public Instruction setRs(Integer rs) {
		this.rs = rs;
		return this;
	}

	public Instruction setRs(String rs) {
		this.rs = Integer.parseInt(rs, 2);
		return this;
	}

	public Integer getRt() {
		return rt;
	}

	public void setRt(Integer rt) {
		this.rt = rt;
	}

	public Instruction setRt(String rt) {
		this.rt = Integer.parseInt(rt, 2);
		return this;
	}

	public Integer getRd() {
		return rd;
	}

	public void setRd(Integer rd) {
		this.rd = rd;
	}

	public void setRd(String rd) {
		this.rd = Integer.parseInt(rd, 2);
	}

	public Integer getShift() {
		return shift;
	}

	public void setShift(Integer shift) {
		this.shift = shift;
	}

	public void setShift(String shift) {
		this.shift = Integer.parseInt(shift, 2);
	}

	public Integer getFunc() {
		return func;
	}

	public void setFunc(Integer func) {
		this.func = func;
	}

	public Integer getValue() {
		return value;
	}

	public void setValue(Integer value) {
		this.value = value;
	}

	public void setValue(String value) {
		this.value = Integer.parseUnsignedInt(value, 2);
	}

	public Integer getTarget() {
		return target;
	}

	public void setTarget(Integer target) {
		this.target = target;
	}

	public String getInstruction() {
		return instruction;
	}

	public void setInstruction(String instrction) {
		this.instruction = instrction;
	}

	public void setInstruction(String instrction, Object... argv) {
		this.instruction = String.format(instrction, argv);
	}

	@Override
	public String toString() {
		return "Instruction{" +
				"category=" + category +
				", address=" + address +
				", operation='" + operation + '\'' +
				", rs=" + rs +
				", rt=" + rt +
				", rd=" + rd +
				", shift=" + shift +
				", func=" + func +
				", value=" + value +
				", target=" + target +
				", instruction='" + instruction + '\'' +
				'}';
	}
}

final class Category {
	public static Map<String, Integer> categorySet;
	public static String[] opType1;
	public static String[] opType2;
	public static Map<String, Operation> operationMap;

	static {
		categorySet = new HashMap<>();
		opType1 = new String[]{"J", "JR", "BEQ", "BLTZ", "BGTZ", "BREAK", "SW", "LW", "SLL", "SRL", "SRA", "NOP"};
		opType2 = new String[]{"ADD", "SUB", "MUL", "AND", "OR", "XOR", "NOR", "SLT", "ADDI", "ANDI", "ORI", "XORI"};
		operationMap = new HashMap<>();
		categorySet.put("01", 1);
		categorySet.put("11", 2);

		{
			operationMap.put("ADD", Integer::sum);
			operationMap.put("SUB", (int x, int y) -> x - y);
			operationMap.put("MUL", (int x, int y) -> x * y);
			operationMap.put("AND", (int x, int y) -> x & y);
			operationMap.put("OR", (int x, int y) -> x | y);
			operationMap.put("XOR", (int x, int y) -> x ^ y);
			operationMap.put("NOR", (int x, int y) -> ~(x | y));
			operationMap.put("SLT", (int rs, int rt) -> {
				return rs < rt ? 1 : 0;
			});
			operationMap.put("ADDI", (int x, int immediateValue)
					-> x + Integer.parseUnsignedInt(Util.signedExtend(Util.int16toString(immediateValue), 32), 2));
			operationMap.put("ANDI", (int x, int immediateValue)
					-> x & Integer.parseUnsignedInt(Util.zeroExtend(Util.int16toString(immediateValue), 32), 2));
			operationMap.put("ORI", (int x, int immediateValue)
					-> x | Integer.parseUnsignedInt(Util.zeroExtend(Util.int16toString(immediateValue), 32), 2));
			operationMap.put("XORI", (int x, int immediateValue)
					-> x ^ Integer.parseUnsignedInt(Util.zeroExtend(Util.int16toString(immediateValue), 32), 2));
			operationMap.put("SLL", (int rt, int sa) -> rt << sa);//逻辑左移
			operationMap.put("SRL", (int rt, int sa) -> rt >>> sa);//逻辑右移
			operationMap.put("SRA", (int rt, int sa) -> rt >> sa);//算术右移
		}

		/*
		 instr_index<<2            26             J target
		 rs padding hint                          JR rs
		 rs rt offset             5 5 16          BEQ rs, rt, offset
		 rs 00000 offset          5 5 16          BLTZ rs, offset
		 rs 00000 offset          5 5 16          BGTZ rs, offset
		 BREAK
		 base rt offset           5 5 16          SW rt, offset(base)
		 base rt offset           5 5 16          LW rt, offset(base)
		 00000 rt rd sa           5 5 5 5       SLL rd, rt, sa
		 00000 rt rd sa           5 5 5 5       SRL rd, rt, sa
		 00000 rt rd sa           5 5 5 5       SRA rd, rt, sa
		                                          NOP
		 rs rt rd         5 5 5        ADD rd, rs, rt
		 rs rt rd         5 5 5        SUB rd, rs, rt
		 rs rt rd         5 5 5        MUL rd, rs, rt
		 rs rt rd         5 5 5        AND rd, rs, rt
		 rs rt rd         5 5 5        OR rd, rs, rt
		 rs rt rd         5 5 5        XOR rd, rs, rt
		 rs rt rd         5 5 5        NOR rd, rs, rt
		 rs rt rd         5 5 5        SLT rd, rs, rt

		 rs rt immediate          5 5 16          ADDI rt, rs, immediate
		 rs rt immediate          5 5 16          ANDI rt, rs, immediate
		 rs rt immediate          5 5 16          ORI rt, rs, immediate
		 rs rt immediate          5 5 16          ANDI rt, rs, immediate*/
	}

	public static Integer getCategory(String category) {
		return categorySet.get(category);
	}

	public static String getOperation(Integer type, Integer opCode) {
		if (type == 1) {
			return opType1[opCode];
		} else return opType2[opCode];
	}
}

interface Operation {
	int operation(int x, int y);
}

final class Util {

	/**
	 * 0扩展到32位
	 *
	 * @param s 待扩展的01字符串
	 * @param i 扩展的位数
	 * @return 扩展到i位的01字符串
	 */
	public static String zeroExtend(String s, int i) {
		int len = s.length();
		if (len == 32) {
			return s;
		}
		return "0".repeat(i - len) + s;
	}

	/**
	 * 符号扩展成32位
	 *
	 * @param s 补码表示的二进制01串
	 */
	public static String signedExtend(String s, int size) {
		int len = s.length(),
				flag = s.charAt(0) == '1' ? -1 : 1;
		if (len == 32) return "";
		StringBuilder builder = new StringBuilder();
		if (flag == 1) {
			s = "0".repeat(size - len) + s;
		} else {
			//if (s.substring(1).equals("0".repeat(len - 1))) {
			//	return "1" + "0".repeat(31);
			//}
			s = "1".repeat(32 - len) + s;
		}
		return s;
	}

	/**
	 * 将二进制补码转为十进制，并返回带符号的数字
	 *
	 * @param complementBinary 二进制01串
	 * @return 带符号的原码
	 */
	public static Integer complement2trueFrom(String complementBinary, int length) {
		int flag = complementBinary.charAt(0) == '1' ? -1 : 1;
		String trueForm = complementBinary;
		if (flag == -1) {
			//负数的补码转源码：
			char[] chars = complementBinary.toCharArray();
			for (int i = 1, len = chars.length; i < len; i++) {
				chars[i] = chars[i] == '1' ? '0' : '1';
			}
			trueForm = String.valueOf(chars);
		}
		//System.out.println(trueForm);

		int value = Integer.parseInt(trueForm.substring(1), 2);
		if (flag == -1) {
			value = flag * ((value + 1) & 0x7FFF);
			if (value == 0)
				value = Integer.MIN_VALUE;
		}
		return value;

	}

	/**
	 * 16位二进制补码转整型
	 *
	 * @param binary 16位二进制补码
	 * @return 整型
	 */
	public static Integer complement16toInteger(String binary) {
		int len = binary.length();
		int flag = binary.charAt(0) == '1' ? -1 : 0;
		if (flag == -1) {
			char[] bits = binary.toCharArray();
			int shift = 1, value = 0;
			for (int i = bits.length - 1; i >= 1; i--) {
				if (bits[i] == '1') {
					value += shift;
				}
				shift <<= 1;
			}
			if (value == 0) {
				return -1 *( 1 << (len - 1));
			}
			value = value + (flag * shift);
			return value;
		} else {
			return Integer.parseInt(binary, 2);
		}
	}

	/**
	 * -32768 ~ 32767 转16位二进制字符串
	 *
	 * @param value value
	 * @return 16位二进制字符串
	 */
	public static String int16toString(Integer value) {
		if (value == -32768) {
			return "1000000000000000";
		}
		if (value > 0) {
			String substring = Integer.toBinaryString(value);
			return "0".repeat(16 - substring.length()) + substring;

		} else if (value == 0) return "0".repeat(16);
		else {
			return Integer.toBinaryString(value).substring(16);
		}
	}

	public static String int2String(Integer value, Integer bits) {
		String s = Integer.toBinaryString(value);
		s = "0".repeat(32 - s.length()) + s;
		return s.substring(32 - bits);
	}


}