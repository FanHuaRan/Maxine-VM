/*
 * Copyright (c) 2007 Sun Microsystems, Inc.  All rights reserved.
 *
 * Sun Microsystems, Inc. has intellectual property rights relating to technology embodied in the product
 * that is described in this document. In particular, and without limitation, these intellectual property
 * rights may include one or more of the U.S. patents listed at http://www.sun.com/patents and one or
 * more additional patents or pending patent applications in the U.S. and in other countries.
 *
 * U.S. Government Rights - Commercial software. Government users are subject to the Sun
 * Microsystems, Inc. standard license agreement and applicable provisions of the FAR and its
 * supplements.
 *
 * Use is subject to license terms. Sun, Sun Microsystems, the Sun logo, Java and Solaris are trademarks or
 * registered trademarks of Sun Microsystems, Inc. in the U.S. and other countries. All SPARC trademarks
 * are used under license and are trademarks or registered trademarks of SPARC International, Inc. in the
 * U.S. and other countries.
 *
 * UNIX is a registered trademark in the U.S. and other countries, exclusively licensed through X/Open
 * Company, Ltd.
 */
/*VCSID=27164aab-035e-41db-b67d-2cea49280af2*/
package com.sun.max.asm.gen.cisc.ia32;

import com.sun.max.asm.gen.*;
import com.sun.max.asm.gen.cisc.*;
import com.sun.max.asm.gen.cisc.x86.*;
import com.sun.max.asm.ia32.*;
import com.sun.max.asm.x86.*;
import com.sun.max.lang.*;
import com.sun.max.program.*;

/**
 * @author Bernd Mathiske
 */
public class IA32Template extends X86Template {

    IA32Template(X86InstructionDescription instructionDescription, int serial, InstructionAssessment instructionFamily, X86TemplateContext context) {
        super(instructionDescription, serial, instructionFamily, context);
    }

    private void addSib(X86Operand.Designation designation) throws TemplateNotNeededException {
        assert context().addressSizeAttribute() != WordWidth.BITS_16;
        haveSibByte();
        switch (context().sibBaseCase()) {
            case GENERAL_REGISTER: {
                switch (context().modCase()) {
                    case MOD_0: {
                        addEnumerableParameter(designation, ParameterPlace.SIB_BASE, IA32BaseRegister32.ENUMERATOR);
                        break;
                    }
                    case MOD_1:
                    case MOD_2: {
                        addEnumerableParameter(designation, ParameterPlace.SIB_BASE, IA32BaseRegister32.ENUMERATOR);
                        break;
                    }
                    case MOD_3: {
                        ProgramError.unexpected("no SIB for mod == 3");
                    }
                }
                break;
            }
            case SPECIAL: {
                switch (context().modCase()) {
                    case MOD_0: {
                        setLabelParameterIndex();
                        addParameter(new X86AddressParameter(designation, WordWidth.BITS_32));
                        break;
                    }
                    default: {
                        ProgramError.unexpected("no special SIB base for mod != 0");
                    }
                }
                break;
            }
        }
        switch (context().sibIndexCase()) {
            case GENERAL_REGISTER:
                addEnumerableParameter(designation, ParameterPlace.SIB_INDEX, IA32IndexRegister32.ENUMERATOR);
                break;
            case NONE:
                // Our external assembler (gas) cannot generate these case and they seem redundant anyway,
                // so for now we do not produce them:
                TemplateNotNeededException.raise();
        }
        addParameter(new X86EnumerableParameter<Scale>(designation, ParameterPlace.SIB_SCALE, Scale.ENUMERATOR));
    }

    @Override
    protected void organize_M(X86Operand.Designation designation) throws TemplateNotNeededException {
        switch (context().modCase()) {
            case MOD_0: {
                switch (context().rmCase()) {
                    case NORMAL:
                        switch (context().addressSizeAttribute()) {
                            case BITS_16:
                                addEnumerableParameter(designation, ParameterPlace.MOD_RM, IA32IndirectRegister16.ENUMERATOR);
                                break;
                            case BITS_32:
                                addEnumerableParameter(designation, ParameterPlace.MOD_RM, IA32IndirectRegister32.ENUMERATOR);
                                break;
                            default:
                                ProgramError.unexpected();
                        }
                        break;
                    case SWORD:
                        switch (context().addressSizeAttribute()) {
                            case BITS_16:
                                setExternalCodeSizeAttribute(context().addressSizeAttribute());
                                addParameter(new X86AddressParameter(designation, WordWidth.BITS_16));
                                break;
                            default:
                                TemplateNotNeededException.raise();
                        }
                        break;
                    case SDWORD:
                        switch (context().addressSizeAttribute()) {
                            case BITS_32:
                                setLabelParameterIndex();
                                addParameter(new X86AddressParameter(designation, WordWidth.BITS_32));
                                break;
                            default:
                                TemplateNotNeededException.raise();
                        }
                        break;
                    case SIB:
                        switch (context().addressSizeAttribute()) {
                            case BITS_32:
                                addSib(designation);
                                break;
                            default:
                                TemplateNotNeededException.raise();
                        }
                        break;
                }
                break;
            }
            case MOD_1: {
                addParameter(new X86DisplacementParameter(designation, WordWidth.BITS_8));
                switch (context().rmCase()) {
                    case NORMAL:
                        switch (context().addressSizeAttribute()) {
                            case BITS_16:
                                addEnumerableParameter(designation, ParameterPlace.MOD_RM, IA32IndirectRegister16.ENUMERATOR);
                                break;
                            case BITS_32:
                                addEnumerableParameter(designation, ParameterPlace.MOD_RM, IA32IndirectRegister32.ENUMERATOR);
                                break;
                            default:
                                ProgramError.unexpected();
                        }
                        break;
                    case SIB:
                        switch (context().addressSizeAttribute()) {
                            case BITS_32:
                                addSib(designation);
                                break;
                            default:
                                TemplateNotNeededException.raise();
                        }
                        break;
                    default:
                        TemplateNotNeededException.raise();
                }
                break;
            }
            case MOD_2: {
                switch (context().addressSizeAttribute()) {
                    case BITS_16:
                        addParameter(new X86DisplacementParameter(designation, WordWidth.BITS_16));
                        break;
                    case BITS_32:
                        addParameter(new X86DisplacementParameter(designation, WordWidth.BITS_32));
                        break;
                    default:
                        ProgramError.unexpected();
                }
                switch (context().rmCase()) {
                    case NORMAL:
                        switch (context().addressSizeAttribute()) {
                            case BITS_16:
                                addEnumerableParameter(designation, ParameterPlace.MOD_RM, IA32IndirectRegister16.ENUMERATOR);
                                break;
                            case BITS_32:
                                addEnumerableParameter(designation, ParameterPlace.MOD_RM, IA32IndirectRegister32.ENUMERATOR);
                                break;
                            default:
                                ProgramError.unexpected();
                        }
                        break;
                    case SIB:
                        switch (context().addressSizeAttribute()) {
                            case BITS_16:
                                throw TemplateNotNeededException.raise();
                            case BITS_32:
                                addSib(designation);
                                break;
                            default:
                                ProgramError.unexpected();
                        }
                        break;
                    default:
                        TemplateNotNeededException.raise();
                }
                break;
            }
            case MOD_3: {
                TemplateNotNeededException.raise();
            }
        }
    }

    public void visitOperandCode(OperandCode operandCode, X86Operand.Designation designation, ArgumentRange argumentRange, TestArgumentExclusion testArgumentExclusion)
        throws TemplateNotNeededException {
        switch (operandCode) {
            case Ap:
                instructionDescription().beNotExternallyTestable(); // gas does not support cross-segment instructions
                switch (context().addressSizeAttribute()) {
                    case BITS_16:
                        setExternalCodeSizeAttribute(context().addressSizeAttribute());
                        addParameter(new X86AddressParameter(designation, WordWidth.BITS_16));
                        break;
                    case BITS_32:
                        setLabelParameterIndex();
                        addParameter(new X86AddressParameter(designation, WordWidth.BITS_32));
                        break;
                    default:
                        ProgramError.unexpected();
                }
                break;
            case Cd: {
                addParameter(new X86EnumerableParameter<ControlRegister>(designation, ParameterPlace.MOD_REG, ControlRegister.ENUMERATOR));
                break;
            }
            case Dd: {
                addParameter(new X86EnumerableParameter<DebugRegister>(designation, ParameterPlace.MOD_REG, DebugRegister.ENUMERATOR));
                break;
            }
            case Eb: {
                organize_E(designation, ParameterPlace.MOD_RM, IA32GeneralRegister8.ENUMERATOR, testArgumentExclusion);
                break;
            }
            case Ed: {
                organize_E(designation, ParameterPlace.MOD_RM, IA32GeneralRegister32.ENUMERATOR, testArgumentExclusion);
                break;
            }
            case Ev: {
                switch (context().operandSizeAttribute()) {
                    case BITS_16:
                        organize_E(designation, ParameterPlace.MOD_RM, IA32GeneralRegister16.ENUMERATOR, testArgumentExclusion);
                        break;
                    case BITS_32:
                        organize_E(designation, ParameterPlace.MOD_RM, IA32GeneralRegister32.ENUMERATOR, testArgumentExclusion);
                        break;
                    default:
                        ProgramError.unexpected();
                }
                break;
            }
            case Ew: {
                organize_E(designation, ParameterPlace.MOD_RM, IA32GeneralRegister16.ENUMERATOR, testArgumentExclusion);
                break;
            }
            case Fv: {
                break;
            }
            case Gb: {
                addEnumerableParameter(designation, ParameterPlace.MOD_REG, IA32GeneralRegister8.ENUMERATOR).excludeTestArguments(
                                testArgumentExclusion);
                break;
            }
            case Gd: {
                addEnumerableParameter(designation, ParameterPlace.MOD_REG, IA32GeneralRegister32.ENUMERATOR).excludeTestArguments(testArgumentExclusion);
                break;
            }
            case Gv: {
                switch (context().operandSizeAttribute()) {
                    case BITS_16:
                        addEnumerableParameter(designation, ParameterPlace.MOD_REG, IA32GeneralRegister16.ENUMERATOR).excludeTestArguments(
                                        testArgumentExclusion);
                        break;
                    case BITS_32:
                        addEnumerableParameter(designation, ParameterPlace.MOD_REG, IA32GeneralRegister32.ENUMERATOR).excludeTestArguments(
                                        testArgumentExclusion);
                        break;
                    default:
                        ProgramError.unexpected();
                }
                break;
            }
            case Gw: {
                addEnumerableParameter(designation, ParameterPlace.MOD_REG, IA32GeneralRegister16.ENUMERATOR).excludeTestArguments(
                                testArgumentExclusion);
                break;
            }
            case Ib: {
                final X86ImmediateParameter parameter = new X86ImmediateParameter(designation, WordWidth.BITS_8);
                addParameter(parameter);
                parameter.setArgumentRange(argumentRange);
                parameter.excludeTestArguments(testArgumentExclusion);
                break;
            }
            case ICb: {
                addEnumerableParameter(designation, ParameterPlace.APPEND, IA32XMMComparison.ENUMERATOR);
                break;
            }
            case Iv: {
                setExternalOperandTypeSuffix(operandCode.operandTypeCode());
                final X86ImmediateParameter parameter = new X86ImmediateParameter(designation, context().operandSizeAttribute());
                addParameter(parameter);
                parameter.setArgumentRange(argumentRange);
                parameter.excludeTestArguments(testArgumentExclusion);
                break;
            }
            case Iw: {
                setExternalCodeSizeAttribute(context().operandSizeAttribute());
                final X86ImmediateParameter parameter = new X86ImmediateParameter(designation, WordWidth.BITS_16);
                addParameter(parameter);
                parameter.setArgumentRange(argumentRange);
                parameter.excludeTestArguments(testArgumentExclusion);
                break;
            }
            case Jb: {
                setExternalCodeSizeAttribute(context().addressSizeAttribute());
                setLabelParameterIndex();
                final X86OffsetParameter parameter = new X86OffsetParameter(designation, WordWidth.BITS_8);
                addParameter(parameter);
                parameter.setArgumentRange(argumentRange);
                parameter.excludeTestArguments(testArgumentExclusion);
                break;
            }
            case Jv: {
                switch (context().operandSizeAttribute()) {
                    case BITS_16:
                        setExternalCodeSizeAttribute(context().operandSizeAttribute());
                        setLabelParameterIndex();
                        addParameter(new X86OffsetParameter(designation, WordWidth.BITS_16));
                        break;
                    case BITS_32:
                        setLabelParameterIndex();
                        addParameter(new X86OffsetParameter(designation, WordWidth.BITS_32));
                        break;
                    default:
                        ProgramError.unexpected();
                }
                break;
            }
            case Md_q: {
                if (operandSizeAttribute() == WordWidth.BITS_16) {
                    TemplateNotNeededException.raise();
                }
                organize_M(designation);
                break;
            }
            case Mdq:
            case Ma:
            case Mp:
            case Mq:
            case Ms:
            case Mv:
            case Mw: {
                organize_M(designation);
                break;
            }
            case Nb: {
                addEnumerableParameter(designation, ParameterPlace.OPCODE1, IA32GeneralRegister8.ENUMERATOR).excludeTestArguments(
                                testArgumentExclusion);
                break;
            }
            case Nd:
                addEnumerableParameter(designation, ParameterPlace.OPCODE2, IA32GeneralRegister32.ENUMERATOR).excludeTestArguments(
                                testArgumentExclusion);
                break;
            case Nv:
                final ParameterPlace place = (opcode2() != null) ? ParameterPlace.OPCODE2 : ParameterPlace.OPCODE1;
                switch (context().operandSizeAttribute()) {
                    case BITS_16:
                        addEnumerableParameter(designation, place, IA32GeneralRegister16.ENUMERATOR).excludeTestArguments(
                                               testArgumentExclusion);
                        break;
                    case BITS_32:
                        addEnumerableParameter(designation, place, IA32GeneralRegister32.ENUMERATOR).excludeTestArguments(
                                               testArgumentExclusion);
                        break;
                    default:
                        ProgramError.unexpected();
                }
                break;
            case Ob:
            case Ov: {
                switch (context().addressSizeAttribute()) {
                    case BITS_16:
                        setExternalCodeSizeAttribute(context().addressSizeAttribute());
                        addParameter(new X86AddressParameter(designation, WordWidth.BITS_16));
                        break;
                    case BITS_32:
                        setLabelParameterIndex();
                        addParameter(new X86AddressParameter(designation, WordWidth.BITS_32));
                        break;
                    default:
                        ProgramError.unexpected();
                }
                break;
            }
            case Pd:
            case Pq: {
                addEnumerableParameter(designation, ParameterPlace.MOD_REG, MMXRegister.ENUMERATOR).excludeTestArguments(testArgumentExclusion);
                break;
            }
            case PRq: {
                if (context().modCase() != X86TemplateContext.ModCase.MOD_3) {
                    TemplateNotNeededException.raise();
                }
                addEnumerableParameter(designation, ParameterPlace.MOD_RM, MMXRegister.ENUMERATOR).excludeTestArguments(testArgumentExclusion);
                break;
            }
            case Qd:
            case Qq: {
                organize_E(designation, ParameterPlace.MOD_RM, MMXRegister.ENUMERATOR, testArgumentExclusion);
                break;
            }
            case Rd: {
                if (context().modCase() != X86TemplateContext.ModCase.MOD_3) {
                    TemplateNotNeededException.raise();
                }
                addEnumerableParameter(designation, ParameterPlace.MOD_RM, IA32GeneralRegister32.ENUMERATOR).excludeTestArguments(
                                testArgumentExclusion);
                break;
            }
            case Rv:
                if (context().modCase() != X86TemplateContext.ModCase.MOD_3) {
                    TemplateNotNeededException.raise();
                }
                switch (context().operandSizeAttribute()) {
                    case BITS_16:
                        addEnumerableParameter(designation, ParameterPlace.MOD_RM, IA32GeneralRegister16.ENUMERATOR).excludeTestArguments(
                                               testArgumentExclusion);
                        break;
                    case BITS_32:
                        addEnumerableParameter(designation, ParameterPlace.MOD_RM, IA32GeneralRegister32.ENUMERATOR).excludeTestArguments(
                                               testArgumentExclusion);
                        break;
                    default:
                        ProgramError.unexpected();
                }
                break;
            case Sw: {
                addEnumerableParameter(designation, ParameterPlace.MOD_REG, SegmentRegister.ENUMERATOR).excludeTestArguments(
                                testArgumentExclusion);
                break;
            }
            case Vpd:
            case Vps:
            case Vq:
            case Vdq:
            case Vsd:
            case Vss: {
                addEnumerableParameter(designation, ParameterPlace.MOD_REG, IA32XMMRegister.ENUMERATOR).excludeTestArguments(testArgumentExclusion);
                break;
            }
            case VRq:
            case VRdq:
            case VRpd:
            case VRps: {
                if (context().modCase() != X86TemplateContext.ModCase.MOD_3) {
                    TemplateNotNeededException.raise();
                }
                addEnumerableParameter(designation, ParameterPlace.MOD_RM, IA32XMMRegister.ENUMERATOR).excludeTestArguments(testArgumentExclusion);
                break;
            }
            case Wdq:
            case Wpd:
            case Wps:
            case Wq:
            case Wsd:
            case Wss: {
                switch (context().operandSizeAttribute()) {
                    case BITS_32:
                    case BITS_64:
                        organize_E(designation, ParameterPlace.MOD_RM, IA32XMMRegister.ENUMERATOR, testArgumentExclusion);
                        break;
                    default:
                        TemplateNotNeededException.raise();
                }
                break;
            }
            case Xb:
            case Xv:
            case Yb:
            case Yv: {
                visitOperandTypeCode(operandCode.operandTypeCode());
                break;
            }
            default:
                ProgramError.unexpected("undefined operand code: " + operandCode);
        }
    }

    public void visitRegisterOperandCode(RegisterOperandCode registerOperandCode, X86Operand.Designation designation, ImplicitOperand.ExternalPresence externalPresence) {
        switch (operandSizeAttribute()) {
            case BITS_16:
                addImplicitOperand(new X86ImplicitOperand(designation, externalPresence, IA32GeneralRegister16.ENUMERATOR.get(registerOperandCode.id())));
                break;
            case BITS_32:
                addImplicitOperand(new X86ImplicitOperand(designation, externalPresence, IA32GeneralRegister32.ENUMERATOR.get(registerOperandCode.id())));
                break;
            default:
                ProgramError.unexpected();
        }
    }
}
