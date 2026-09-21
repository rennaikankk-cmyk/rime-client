package dev.guizhou.util.render.font;

import java.util.concurrent.atomic.AtomicInteger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;

/** Failure injection without a graphics device, plus checks on the actual rendering cleanup paths. */
public final class FontRecoveryTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        for (Throwable failure : new Throwable[]{new RuntimeException("draw failed"),new UnsatisfiedLinkError("font library unavailable"),new java.awt.AWTError("font subsystem unavailable")}) {
            AtomicInteger reports=new AtomicInteger(),attempts=new AtomicInteger();
            var guard=new FontBackendGuard(true,(phase,cause)->{check(cause==failure,"Preserve original diagnostic cause");reports.incrementAndGet();});
            int width=guard.use("measure",()->{attempts.incrementAndGet();if(failure instanceof Error e)throw e;throw (RuntimeException)failure;},()->37);
            check(width==37,"Use native width instead of zero");
            check(!guard.enabled(),"Disable failed backend for the session");
            for(int i=0;i<5;i++)check(guard.use("draw",()->{attempts.incrementAndGet();return false;},()->true),"Keep native fallback active");
            check(attempts.get()==1&&reports.get()==1,"No retry or log flood");
        }
        var forced=new FontBackendGuard(false,(phase,failure)->{throw new AssertionError("Forced mode is not a failure");});
        check(forced.use("draw",()->{throw new AssertionError("Must not initialize custom fonts");},()->42)==42,"Forced native mode skips custom backend");
        var healthy=new FontBackendGuard(true,(phase,failure)->{throw new AssertionError("Healthy path logged");});
        check(healthy.use("draw",()->12,()->{throw new AssertionError("Unnecessary fallback");})==12,"Healthy custom path retained");
        try {forced.use("draw",()->0,()->{throw new IllegalStateException("native failure");});throw new AssertionError("Native failure swallowed");}
        catch(IllegalStateException expected){check(expected.getMessage().equals("native failure"),"Native errors propagate");}
        var fatal=new FontBackendGuard(true,(phase,failure)->{throw new AssertionError("Fatal VM error logged as recoverable");});
        try{fatal.use("draw",()->{throw new OutOfMemoryError("fixture");},()->0);throw new AssertionError("VM error swallowed");}
        catch(OutOfMemoryError expected){check(fatal.enabled(),"Fatal VM errors are not suppressed");}
        for(String owner:new String[]{"FontRenderer","HudFonts","FontFallback"}) {
            ClassNode node=read(owner);int scoped=0;
            for(MethodNode method:node.methods)if(calls(method,"pushPose")){
                scoped++;
                check(calls(method,"popPose"),owner+" restores render pose");
                check(method.tryCatchBlocks.stream().anyMatch(block->block.type==null&&handlerCalls(block.handler,"popPose")),owner+" restores pose even when drawing throws");
            }
            check(scoped>0,owner+" render scope exercised");
        }
        MethodNode override=read("Fonts").methods.stream().filter(m->m.name.equals("drawOverrideActive")).findFirst().orElseThrow();
        check(calls(override,"nativePass"),"Vanilla fallback cannot recursively enter custom font mixins");
        System.out.println("FontRecoveryTest passed: "+checks+" checks");
    }
    private static ClassNode read(String name)throws Exception {
        ClassNode node=new ClassNode();
        try(var stream=FontRecoveryTest.class.getResourceAsStream("/dev/guizhou/util/render/font/"+name+".class")){
            if(stream==null)throw new AssertionError("Missing "+name);new ClassReader(stream).accept(node,0);
        }return node;
    }
    private static boolean calls(MethodNode method,String name){for(var i:method.instructions)if(i instanceof MethodInsnNode call&&call.name.equals(name))return true;return false;}
    private static boolean handlerCalls(AbstractInsnNode start,String name){for(var i=start;i!=null;i=i.getNext()){if(i instanceof MethodInsnNode call&&call.name.equals(name))return true;if(i.getOpcode()==org.objectweb.asm.Opcodes.ATHROW)return false;}return false;}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
}
