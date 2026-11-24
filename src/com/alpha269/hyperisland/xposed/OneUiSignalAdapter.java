package com.alpha269.hyperisland.xposed;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Subscribes through the installed One UI listener APIs, without writing its state fields. */
public final class OneUiSignalAdapter implements AutoCloseable {
    public interface Observer {void state(int value);void dozing(boolean value);void shade(float value);void window(int value);}
    private final Observer observer;
    private volatile Binding status,shade;
    private volatile boolean closed;
    public OneUiSignalAdapter(Observer observer){this.observer=observer;}
    public synchronized void bindStatus(Object owner)throws Exception{
        if(closed||owner==null)return;
        if(status==null||status.owner.get()!=owner){if(status!=null)status.close();status=null;
            snapshotStatus(owner);
            status=new Binding(owner,"addCallback","removeCallback","com.android.systemui.plugins.statusbar.StatusBarStateController$StateListener",(method,args)->{
                if(closed)return;
                if(method.equals("onStateChanged")&&args.length>0&&args[0] instanceof Number)observer.state(((Number)args[0]).intValue());
                if(method.equals("onDozingChanged")&&args.length>0&&args[0] instanceof Boolean)observer.dozing((Boolean)args[0]);
            });}
        snapshotStatus(owner);
    }
    public void snapshotStatus(Object owner){
        if(closed||owner==null)return;
        Binding current=status;if(current!=null&&current.owner.get()!=owner)return;
        Object state=invoke(owner,"getState"),dozing=invoke(owner,"isDozing");
        if(state instanceof Number)observer.state(((Number)state).intValue());
        if(dozing instanceof Boolean)observer.dozing((Boolean)dozing);
    }
    public synchronized void bindShade(Object owner)throws Exception{
        if(closed||owner==null)return;
        if(shade==null||shade.owner.get()!=owner){if(shade!=null)shade.close();
            shade=new Binding(owner,"addExpansionListener","removeExpansionListener","com.android.systemui.shade.ShadeExpansionListener",(method,args)->{
                if(closed||!method.equals("onPanelExpansionChanged")||args.length==0)return;
                Object fraction=field(args[0],"fraction");
                if(fraction instanceof Number){
                    float value=((Number)fraction).floatValue();
                    if(Boolean.FALSE.equals(field(args[0],"expanded"))&&Boolean.FALSE.equals(field(args[0],"tracking")))value=0f;
                    observer.shade(value);
                }
            });}
        Object fraction=field(owner,"fraction");if(fraction instanceof Number)observer.shade(((Number)fraction).floatValue());
    }
    public void onShadeClosed(){if(!closed)observer.shade(0f);}
    public void snapshotWindow(Object owner){
        if(closed||owner==null||!Integer.valueOf(0).equals(field(owner,"thisDisplayId")))return;
        Object value=field(owner,"windowState");if(value instanceof Number)observer.window(((Number)value).intValue());
    }
    @Override public synchronized void close(){closed=true;if(status!=null)status.close();if(shade!=null)shade.close();status=shade=null;}
    private interface Callback {void accept(String method,Object[] args);}
    private static final class Binding {
        final WeakReference<Object> owner;final Object proxy;final Method remove;
        volatile boolean active=true;
        Binding(Object source,String addName,String removeName,String interfaceName,Callback callback)throws Exception{
            owner=new WeakReference<>(source);Method add=null;
            for(Method method:source.getClass().getMethods())if(method.getName().equals(addName)&&method.getParameterTypes().length==1
                &&method.getParameterTypes()[0].isInterface()
                &&(interfaceName.indexOf('.')>=0?method.getParameterTypes()[0].getName().equals(interfaceName)
                    :method.getParameterTypes()[0].getSimpleName().equals(interfaceName))){add=method;break;}
            // Some implementations expose only the erased CallbackController bridge.
            Class<?> type;
            if(add==null&&interfaceName.indexOf('.')>=0){
                type=Class.forName(interfaceName,false,source.getClass().getClassLoader());
                add=source.getClass().getMethod(addName,Object.class);
            }else type=add==null?null:add.getParameterTypes()[0];
            if(add==null)throw new NoSuchMethodException(addName+"("+interfaceName+")");
            remove=source.getClass().getMethod(removeName,add.getParameterTypes()[0]);remove.setAccessible(true);
            proxy=Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(self,method,args)->{
                if(method.getDeclaringClass()==Object.class){
                    if(method.getName().equals("hashCode"))return System.identityHashCode(self);
                    if(method.getName().equals("equals"))return args!=null&&args.length==1&&self==args[0];
                    return "HyperBar "+interfaceName;
                }
                if(active)try{callback.accept(method.getName(),args==null?new Object[0]:args);}catch(RuntimeException ignored){}return null;
            });
            add.setAccessible(true);
            try{add.invoke(source,proxy);}catch(Exception failure){active=false;try{remove.invoke(source,proxy);}catch(Exception ignored){}throw failure;}
        }
        void close(){active=false;Object source=owner.get();if(source!=null)try{remove.invoke(source,proxy);}catch(Exception ignored){}}
    }
    private static Object invoke(Object owner,String name){try{Method method=owner.getClass().getMethod(name);method.setAccessible(true);return method.invoke(owner);}catch(Exception ignored){return null;}}
    private static Object field(Object owner,String name){if(owner==null)return null;for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass())try{Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(owner);}catch(Exception ignored){}return null;}
}
