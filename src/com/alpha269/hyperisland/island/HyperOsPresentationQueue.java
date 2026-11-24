package com.alpha269.hyperisland.island;
import java.util.*;

/** Android data/capability identities around the Rust ordinary-phone presentation chain. */
public final class HyperOsPresentationQueue implements AutoCloseable {
    private long handle=HyperOsNative.nativeCreatePresentation(),next=1;
    private final Map<String,Long> ids=new LinkedHashMap<>();
    private final Map<Long,IslandItem> items=new HashMap<>();
    private Layout layout=new Layout(Collections.emptyList(),Collections.emptyList(),false);
    public static final class Layout {
        public final List<IslandItem> visible,hidden;public final boolean emptySentinel;
        Layout(List<IslandItem> visible,List<IslandItem> hidden,boolean empty){this.visible=visible;this.hidden=hidden;emptySentinel=empty;}
    }
    public void sync(List<IslandItem> source){
        requireOpen();Map<Long,IslandItem> current=new HashMap<>();Set<String> present=new HashSet<>();long[] keys=new long[source.size()];long preferred=0;
        for(int i=0;i<source.size();i++){
            IslandItem item=source.get(i);if(!present.add(item.key))throw new IllegalArgumentException("Duplicate presentation source");
            Long id=ids.get(item.key);if(id==null){if(next<=0)throw new IllegalStateException("Presentation IDs exhausted");id=next++;ids.put(item.key,id);}
            keys[i]=id;current.put(id,item);if(preferred==0&&item.kind==IslandItem.Kind.CALL)preferred=id;
        }
        HyperOsNative.nativeSyncPresentation(handle,keys,preferred);ids.keySet().retainAll(present);items.clear();items.putAll(current);snapshot();
    }
    public void swipe(int direction){requireOpen();HyperOsNative.nativePresentationCommand(handle,direction<0?0:1,0);snapshot();}
    public void minimize(IslandItem item){if(item==null||item.kind==IslandItem.Kind.CALL)return;Long id=ids.get(item.key);if(id!=null){HyperOsNative.nativePresentationCommand(handle,2,id);snapshot();}}
    public void focus(IslandItem item){if(item==null)return;Long id=ids.get(item.key);if(id!=null){HyperOsNative.nativePresentationCommand(handle,3,id);snapshot();}}
    public Layout layout(){return layout;}
    public Layout preview(int direction){
        requireOpen();long[] values=new long[items.size()+4];
        HyperOsNative.nativePresentationPreview(handle,direction,values);
        return decode(values);
    }
    private void snapshot(){
        long[] values=new long[items.size()+4];HyperOsNative.nativePresentationSnapshot(handle,values);
        layout=decode(values);
    }
    private Layout decode(long[] values){
        if(values[2]<0||values[2]>items.size()+1)throw new IllegalStateException("Invalid native hidden count");
        List<IslandItem> visible=new ArrayList<>(),hidden=new ArrayList<>();
        for(int i=0;i<2;i++)if(values[i]!=0)visible.add(item(values[i]));boolean empty=false;
        for(int i=0;i<values[2];i++){long id=values[i+3];if(id==0)empty=true;else hidden.add(item(id));}
        return new Layout(Collections.unmodifiableList(visible),Collections.unmodifiableList(hidden),empty);
    }
    private IslandItem item(long id){IslandItem item=items.get(id);if(item==null)throw new IllegalStateException("Missing presentation source identity");return item;}
    private void requireOpen(){if(handle==0)throw new IllegalStateException("Presentation queue closed");}
    @Override public void close(){long id=handle;handle=0;if(id!=0)HyperOsNative.nativeDestroyPresentation(id);ids.clear();items.clear();layout=new Layout(Collections.emptyList(),Collections.emptyList(),false);}
}
