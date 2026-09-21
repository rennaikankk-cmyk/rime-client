package dev.guizhou.features.gui;

import dev.guizhou.util.compat.input.CharacterEvent;
import dev.guizhou.util.compat.input.KeyEvent;
import static org.lwjgl.glfw.GLFW.*;

/** Exercise real input state without a game window: Unicode, selection and empty-field boundaries. */
public final class ShadcnTextFieldTest {
    private static int checks;
    public static void main(String[] args) {
        ShadcnTextField field=new ShadcnTextField("甲💎飞",s->{});field.focused=true;
        key(field,GLFW_KEY_LEFT,0);key(field,GLFW_KEY_BACKSPACE,0);equal(field,"甲飞");
        field.character(new CharacterEvent(0x1F680,0));equal(field,"甲🚀飞");
        key(field,GLFW_KEY_HOME,0);key(field,GLFW_KEY_RIGHT,GLFW_MOD_SHIFT);field.character(new CharacterEvent('乙',0));equal(field,"乙🚀飞");
        key(field,GLFW_KEY_DELETE,0);equal(field,"乙飞");
        key(field,GLFW_KEY_A,GLFW_MOD_CONTROL);key(field,GLFW_KEY_BACKSPACE,0);equal(field,"");
        for(int k:new int[]{GLFW_KEY_LEFT,GLFW_KEY_RIGHT,GLFW_KEY_BACKSPACE,GLFW_KEY_DELETE}) {
            key(field,k,0);equal(field,"");key(field,k,GLFW_MOD_CONTROL);equal(field,"");
        }
        field.character(new CharacterEvent('a',0));field.character(new CharacterEvent(' ',0));field.character(new CharacterEvent('b',0));
        key(field,GLFW_KEY_BACKSPACE,GLFW_MOD_CONTROL);equal(field,"a ");
        key(field,GLFW_KEY_HOME,0);key(field,GLFW_KEY_END,GLFW_MOD_SHIFT);field.character(new CharacterEvent('中',0));equal(field,"中");
        field.focused=false;field.character(new CharacterEvent('x',0));key(field,GLFW_KEY_BACKSPACE,0);equal(field,"中");
        System.out.println("ShadcnTextFieldTest passed: "+checks+" checks");
    }
    private static void key(ShadcnTextField field,int key,int mods){field.key(new KeyEvent(key,0,mods));}
    private static void equal(ShadcnTextField field,String expected){checks++;if(!field.value().equals(expected))throw new AssertionError(field.value()+" != "+expected);}
}
