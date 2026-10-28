package com.bgeforce.tv.validation;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Native TV navigation/visual test. Synthetic metadata and local art; no account/session requests. */
public final class TvUiSmoke extends Instrumentation {
  private int checks;
  private Activity activity;
  private ClassLoader loader;
  private static final class QuietPool extends ThreadPoolExecutor {
    QuietPool(){super(1,1,1,TimeUnit.SECONDS,new LinkedBlockingQueue<Runnable>());}
    @Override public void execute(Runnable task){} // Only this harness blocks the catalog network.
  }
  private static Object get(Object o,String name)throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
  private static void set(Object o,String name,Object value)throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
  private Object invoke(Object o,String name,Class<?>[] types,Object... args)throws Exception {Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
  private void page(String name)throws Exception {invoke(activity,name,new Class<?>[]{});}
  private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
  private void ui(Runnable work){runOnMainSync(work);waitForIdleSync();}
  private void pause()throws Exception {Thread.sleep(1200);waitForIdleSync();}
  @Override public void callActivityOnCreate(Activity a,Bundle b){
    if(a.getClass().getName().equals("com.bgeforce.tv.MainActivity"))try{set(a,"jobs",new QuietPool());}catch(Exception e){throw new RuntimeException(e);}
    super.callActivityOnCreate(a,b);
  }
  public void onCreate(Bundle b){super.onCreate(b);start();}
  private void save(String name)throws Exception {
    Bitmap image=getUiAutomation().takeScreenshot();check(image!=null,"screenshot "+name);
    File dest=new File(getTargetContext().getExternalFilesDir(null),name+".png");
    try(FileOutputStream out=new FileOutputStream(dest)){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();
  }
  private String content(View v){
    StringBuilder b=new StringBuilder();if(v instanceof TextView)b.append(((TextView)v).getText()).append(' ');
    if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)b.append(content(((ViewGroup)v).getChildAt(i)));
    return b.toString();
  }
  private void installLocalArt(Object images)throws Exception {
    Class<?> interceptor=loader.loadClass("okhttp3.Interceptor"),media=loader.loadClass("okhttp3.MediaType"),body=loader.loadClass("okhttp3.ResponseBody"),request=loader.loadClass("okhttp3.Request"),protocol=loader.loadClass("okhttp3.Protocol");
    Object hook=java.lang.reflect.Proxy.newProxyInstance(loader,new Class<?>[]{interceptor},(proxy,method,args)->{
      if(!method.getName().equals("intercept"))return null;
      Object chain=args[0],req=loader.loadClass("okhttp3.Interceptor$Chain").getMethod("request").invoke(chain);
      Object url=request.getMethod("url").invoke(req);String path=(String)url.getClass().getMethod("encodedPath").invoke(url);
      String filename=path.substring(path.lastIndexOf('/')+1);byte[] bytes;
      try(InputStream input=getContext().getAssets().open(filename);ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] block=new byte[8192];int n;while((n=input.read(block))>0)out.write(block,0,n);bytes=out.toByteArray();
      }
      Object mime=media.getMethod("get",String.class).invoke(null,"image/jpeg");Object responseBody=body.getMethod("create",byte[].class,media).invoke(null,bytes,mime);
      Class<?> builder=loader.loadClass("okhttp3.Response$Builder");Object b=builder.newInstance();
      builder.getMethod("request",request).invoke(b,req);builder.getMethod("protocol",protocol).invoke(b,Enum.valueOf((Class)protocol,"HTTP_1_1"));builder.getMethod("code",int.class).invoke(b,200);builder.getMethod("message",String.class).invoke(b,"Local visual fixture");builder.getMethod("body",body).invoke(b,responseBody);return builder.getMethod("build").invoke(b);
    });
    Class<?> builder=loader.loadClass("okhttp3.OkHttpClient$Builder");Object b=builder.newInstance();builder.getMethod("addInterceptor",interceptor).invoke(b,hook);set(images,"http",builder.getMethod("build").invoke(b));
  }
  private void fillGrid(List<Object> games)throws Exception {
    ViewGroup content=(ViewGroup)get(activity,"content");Object grid=findRecycler(content);Object adapter=grid.getClass().getMethod("getAdapter").invoke(grid);
    ((List)get(adapter,"games")).addAll(games);loader.loadClass("androidx.recyclerview.widget.RecyclerView$Adapter").getMethod("notifyDataSetChanged").invoke(adapter);
  }
  private Object findRecycler(View v){if(v.getClass().getName().equals("androidx.recyclerview.widget.RecyclerView"))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Object r=findRecycler(((ViewGroup)v).getChildAt(i));if(r!=null)return r;}return null;}
  public void onStart(){
    Bundle result=new Bundle();
    try{
      if (Build.VERSION.SDK_INT >= 29) getUiAutomation().adoptShellPermissionIdentity("android.permission.INJECT_EVENTS");
      Context c=getTargetContext();loader=c.getClassLoader();
      c.getSharedPreferences("preferences",0).edit().putString("language","pt-BR").commit();
      Intent start=new Intent().setClassName(c,"com.bgeforce.tv.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      activity=startActivitySync(start);waitForIdleSync();
      final List<Object> games=new ArrayList<>();
      String[] names={"Cyberpunk 2077","Forza Horizon 5","Baldur’s Gate 3","Counter-Strike 2","The Witcher 3","No Man’s Sky"};
      for(int i=0;i<names.length;i++){
        JSONObject game=new JSONObject().put("id","fixture-"+i).put("title",names[i]).put("shortDescription","Explore novos mundos e jogue na nuvem. Dados locais exclusivos deste ensaio visual.").put("publisherName","Visual fixture").put("genres",new JSONArray().put("Aventura").put("Ação"))
          .put("images",new JSONObject().put("GAME_BOX_ART","https://fixture.invalid/cover-"+i+".jpg").put("MARQUEE_HERO_IMAGE","https://fixture.invalid/hero-"+i+".jpg"))
          .put("variants",new JSONArray().put(new JSONObject().put("id",10000+i).put("appStore","STEAM").put("gfn",new JSONObject().put("status","AVAILABLE").put("library",new JSONObject().put("status","MANUAL").put("lastPlayedDate","2026-10-28")))));
        games.add(loader.loadClass("com.bgeforce.tv.Game").getConstructor(JSONObject.class).newInstance(game));
      }
      ui(()->{
        try{
          page("shell");Object home=loader.loadClass("com.bgeforce.tv.Catalog$Home").newInstance();((List)get(home,"banners")).addAll(games.subList(0,3));
          Class<?> shelf=loader.loadClass("com.bgeforce.tv.Catalog$Shelf");
          for(String title:new String[]{"Mais populares","Quinta GFN","Grátis para jogar","Adicionados recentemente"})((List)get(home,"shelves")).add(shelf.getConstructor(String.class,String.class,List.class).newInstance(title,title,games));
          set(activity,"home",home);set(activity,"library",new ArrayList<>(games));installLocalArt(get(activity,"images"));page("showHome");
        }catch(Exception e){throw new RuntimeException(e);}
      });pause();
      ui(()->{try{
        check(((LinearLayout)get(activity,"side")).getOrientation()==LinearLayout.HORIZONTAL,"top navigation");
        check(((View)get(activity,"searchButton")).getVisibility()==View.VISIBLE,"home search");
        check(!content((View)get(activity,"content")).contains("Volte a jogar"),"no duplicate recent shelf");
      }catch(Exception e){throw new RuntimeException(e);}});
      android.view.accessibility.AccessibilityNodeInfo activeRoot = getUiAutomation().getRootInActiveWindow();
      check(activeRoot == null || activeRoot.findAccessibilityNodeInfosByText("System UI isn't responding").isEmpty(), "emulator System UI must be responsive");
      save("ui-home");
      ui(()->{try{Object list=get(activity,"homeList");list.getClass().getMethod("scrollToPosition",int.class).invoke(list,3);}catch(Exception e){throw new RuntimeException(e);}});pause();
      ui(()->{try{Object list=get(activity,"homeList");list.getClass().getMethod("scrollToPosition",int.class).invoke(list,0);}catch(Exception e){throw new RuntimeException(e);}});pause();
      ui(()->{int id=c.getResources().getIdentifier("home_primary","id",c.getPackageName());View play=activity.findViewById(id);check(play!=null,"hero recycled/restored");play.requestFocus();Rect rect=new Rect();play.getGlobalVisibleRect(rect);check(rect.height()==play.getHeight(),"hero button not clipped after scrolling");});pause();save("ui-home-restored");
      ui(()->{try{
        invoke(activity,"details",new Class<?>[]{loader.loadClass("com.bgeforce.tv.Game")},games.get(0));
        Object seat=loader.loadClass("com.bgeforce.tv.SessionClient$Seat").newInstance();set(seat,"id","fixture-active");set(seat,"base","https://fixture.nvidiagrid.net/");set(seat,"raw",new JSONObject().put("sessionRequestData",new JSONObject().put("appId","10000")));
        Constructor<?> ctor=loader.loadClass("com.bgeforce.tv.SessionClient$Active").getDeclaredConstructors()[0];ctor.setAccessible(true);Object active=ctor.newInstance(seat);set(activity,"activeSessions",new ArrayList<>(Arrays.asList(active)));page("updateResumeDetails");
        TextView resume=(TextView)get(activity,"resumeDetails"),close=(TextView)get(activity,"closeDetails");
        check(resume.getVisibility()==View.VISIBLE&&close.getVisibility()==View.VISIBLE,"active session exposes resume and close");
        check(((ViewGroup)close.getParent()).indexOfChild(close)>((ViewGroup)resume.getParent()).indexOfChild(resume),"close below resume");resume.requestFocus();
      }catch(Exception e){throw new RuntimeException(e);}});pause();save("ui-details");
      ui(()->{try{((View)get(activity,"closeDetails")).performClick();}catch(Exception e){throw new RuntimeException(e);}});pause();save("ui-close-confirmation");sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync();
      ui(()->{try{check(!((Boolean)get(activity,"closingSession")),"cancel close does not mutate session");page("showSettings");check(((View)get(activity,"searchButton")).getVisibility()==View.GONE,"settings search hidden");check(content((View)get(activity,"content")).contains("1080" )||content((View)get(activity,"content")).contains("720"),"settings profile visible");}catch(Exception e){throw new RuntimeException(e);}});pause();save("ui-settings");
      ui(()->{try{invoke(activity,"showGrid",new Class<?>[]{String.class,boolean.class,String.class,JSONObject.class},"Catálogo",false,"",null);fillGrid(games);check(((View)get(activity,"searchButton")).getVisibility()==View.VISIBLE,"catalog search visible");}catch(Exception e){throw new RuntimeException(e);}});pause();save("ui-catalog");
      ui(()->{try{invoke(activity,"showGrid",new Class<?>[]{String.class,boolean.class,String.class,JSONObject.class},"Minha biblioteca",true,"",null);fillGrid(games);}catch(Exception e){throw new RuntimeException(e);}});pause();save("ui-library");
      check(activity.getApplicationInfo().banner!=0,"TV launcher banner");
      result.putString("result","TV UI checks passed; local synthetic metadata/art, no live account or streaming benchmark");
    }catch(Throwable failure){result.putString("failure",failure.toString());StringWriter trace=new StringWriter();failure.printStackTrace(new PrintWriter(trace));result.putString("trace",trace.toString());}
    finally{if(activity!=null)ui(()->activity.finish());}
    result.putInt("checks",checks);finish(Activity.RESULT_OK,result);
  }
}
