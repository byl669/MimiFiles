package com.miyuetech.mimifiles.core.shareserver.http

/**
 * 内置网页。
 *
 * ## 为什么不用 elFinder（方案原稿写的是"内置 elFinder 精简 H5"）
 * 方案 §10.2 曾计划 `assets/elfinder.zip` + 首启解压（参考应用的做法，约 1.5 MB）。
 * 实际落地时改成本文件内嵌一个自包含页面，理由三条：
 *
 *  1. **信任面**：elFinder 是完整的文件管理器（含压缩、编辑器插件、命令历史…），
 *     它自带的服务端连接器是 PHP。要在 Android 上用它，得自己照着它的协议
 *     重写一遍连接器——**写出来的正是我们本来想省掉的那部分**；
 *  2. **体积**：解压后 1.5 MB 的静态资源只为"浏览 + 上传 + 下载"这三件事，
 *     而我们需要的界面（列表 + 面包屑 + 上传 + 拖放）不到 20 KB；
 *  3. **一致性**：自研页面可以严格跟随 `readOnly`（只读时不渲染任何写入口），
 *     而 elFinder 的能力集比我们的后端大，需要一层层关掉。
 *
 * 换来的代价是"没有 elFinder 的多选批量、压缩包在线解压"这类高级功能——
 * 那些在方案里本来就是 P1/P2（M5+），届时按需在 [ShareWebPage.PAGE] 上增量做，
 * 而不是背一整套第三方前端的维护成本。
 *
 * ## 页面契约（服务端必须满足）
 * | 方法 | 路径 | 语义 |
 * |---|---|---|
 * | GET | `/api/info` | 服务器信息（名称/只读/根路径/版本） |
 * | GET | `/api/list?path=` | 列目录 |
 * | GET | `/f/<rel>` | **内联**取文件（图片/视频/PDF 直接在标签页里看，支持 Range） |
 * | GET | `/d/<rel>` | **下载**取文件（`Content-Disposition: attachment`） |
 * | PUT | `/f/<rel>` | 上传（裸 body，可带 `Content-Length`；流式，不落临时文件） |
 * | POST | `/api/mkdir?path=&name=` | 新建目录 |
 * | POST | `/api/rename?path=&name=` | 重命名 |
 * | POST | `/api/move?path=&dir=` | 移动 |
 * | POST | `/api/delete?path=&recursive=` | 删除 |
 *
 * ⚠️ **所有 `path` / `name` 都必须 `encodeURIComponent`**。NanoHTTPD 用
 * `URLDecoder` 解 URI，它会把 `+` 当成空格；`encodeURIComponent` 会把 `+`（以及
 * `&` `#` `%`）都编成 `%XX`，正好绕开这一点。
 */
object ShareWebPage {

    /** 占位符：服务端插值安全区。用替换而不是 Kotlin 字符串模板，避免与 JS 里的 `${` 打架。 */
    const val PLACEHOLDER_SERVER = "__MIMI_SERVER__"
    const val PLACEHOLDER_ROOT = "__MIMI_ROOT__"
    const val PLACEHOLDER_READONLY = "__MIMI_READONLY__"
    const val PLACEHOLDER_FILECOUNT = "__MIMI_FILECOUNT__"

    /**
     * 渲染页面。
     *
     * 插值前**必须**转义：设备名与根路径都可能包含 `<` `"`（用户给 NAS 起的名字
     * 什么字符都可能有），直接拼进去就是一处 XSS——而这个页面的访问者
     * 正是局域网里的任何一台设备。
     */
    fun render(serverName: String, root: String, readOnly: Boolean, itemCount: Int): String =
        PAGE
            .replace(PLACEHOLDER_SERVER, escapeHtml(serverName))
            .replace(PLACEHOLDER_ROOT, escapeHtml(root))
            .replace(PLACEHOLDER_READONLY, if (readOnly) "true" else "false")
            .replace(PLACEHOLDER_FILECOUNT, itemCount.toString())

    private fun escapeHtml(raw: String): String {
        val sb = StringBuilder(raw.length + 16)
        for (c in raw) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&#39;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * 页面本体。
     *
     * ⚠️ 这是 Kotlin 原样字符串（`"""`）。JS 里**不允许出现 `$`**——那会被当成
     * 模板插值起点。所以整个脚本用字符串拼接、不用模板字面量。这不是洁癖：
     * 混进去一个 `$` 会让编译期报出一堆与真正错误无关的语法错，排查很费时。
     */
    @Suppress("MaxLineLength")
    val PAGE: String = """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>密密文件管理 · 无线分享</title>
<style>
:root{--bg:#f5f6f8;--card:#fff;--text:#1c1d21;--sub:#6b6f76;--line:#e3e5e9;--pri:#2f7d4f;--pri-weak:#e8f4ec;--danger:#c0392b;--shadow:0 1px 3px rgba(0,0,0,.08)}
@media (prefers-color-scheme:dark){:root{--bg:#16181c;--card:#1f2228;--text:#e9eaec;--sub:#9aa0a8;--line:#2c3038;--pri:#4caf72;--pri-weak:#1d2f24;--danger:#e0705f;--shadow:0 1px 3px rgba(0,0,0,.4)}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--text);font:15px/1.5 -apple-system,"Segoe UI","Noto Sans SC","Microsoft YaHei",sans-serif}
header{position:sticky;top:0;background:var(--card);border-bottom:1px solid var(--line);padding:14px 18px;z-index:5}
.brand{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.brand h1{margin:0;font-size:17px;font-weight:600}
.badge{font-size:12px;padding:2px 8px;border-radius:10px;background:var(--pri-weak);color:var(--pri);white-space:nowrap}
.badge.ro{background:#fdecea;color:var(--danger)}
@media (prefers-color-scheme:dark){.badge.ro{background:#3a2320}}
.sub{margin-top:4px;font-size:12.5px;color:var(--sub);word-break:break-all}
main{max-width:960px;margin:0 auto;padding:14px 18px 40px}
.bar{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:12px}
.crumbs{flex:1 1 auto;min-width:0;font-size:13.5px;color:var(--sub);word-break:break-all}
.crumbs a{color:var(--pri);text-decoration:none;cursor:pointer}
.crumbs a:hover{text-decoration:underline}
button{font:inherit;font-size:13.5px;padding:7px 13px;border-radius:8px;border:1px solid var(--line);background:var(--card);color:var(--text);cursor:pointer}
button:hover{border-color:var(--pri);color:var(--pri)}
button.pri{background:var(--pri);border-color:var(--pri);color:#fff}
button.pri:hover{filter:brightness(1.08);color:#fff}
button:disabled{opacity:.5;cursor:not-allowed}
#drop{border:2px dashed var(--line);border-radius:12px;padding:18px;text-align:center;color:var(--sub);font-size:13.5px;margin-bottom:12px;transition:.15s}
#drop.on{border-color:var(--pri);background:var(--pri-weak);color:var(--pri)}
ul.list{list-style:none;margin:0;padding:0;background:var(--card);border:1px solid var(--line);border-radius:12px;overflow:hidden;box-shadow:var(--shadow)}
li.row{display:flex;align-items:center;gap:12px;padding:11px 14px;border-bottom:1px solid var(--line)}
li.row:last-child{border-bottom:none}
li.row:hover{background:var(--pri-weak)}
.nm{flex:1 1 auto;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.nm a{color:inherit;text-decoration:none;cursor:pointer}
.nm a:hover{color:var(--pri);text-decoration:underline}
.dir .nm a{font-weight:600}
.meta{flex:0 0 auto;font-size:12px;color:var(--sub);white-space:nowrap}
.act{flex:0 0 auto;display:flex;gap:4px;opacity:.35;transition:.15s}
li.row:hover .act{opacity:1}
.act button{padding:3px 8px;font-size:12px}
.empty{text-align:center;padding:34px 16px;color:var(--sub);font-size:13.5px}
#pg{position:fixed;left:0;right:0;bottom:0;background:var(--card);border-top:1px solid var(--line);padding:10px 18px;display:none;font-size:13px}
#pgt{height:5px;background:var(--line);border-radius:3px;overflow:hidden;margin-top:6px}
#pgb{height:100%;width:0;background:var(--pri);transition:width .15s}
#toast{position:fixed;left:50%;bottom:74px;transform:translateX(-50%);background:#333;color:#fff;padding:9px 16px;border-radius:8px;font-size:13px;opacity:0;pointer-events:none;transition:.25s;max-width:80vw}
#toast.on{opacity:.95}
#toast.err{background:var(--danger)}
footer{max-width:960px;margin:0 auto;padding:0 18px 30px;color:var(--sub);font-size:12px}
@media (max-width:520px){.meta{display:none}}
</style>
</head>
<body>
<header>
  <div class="brand">
    <h1>密密文件管理</h1>
    <span class="badge" id="b-name">__MIMI_SERVER__</span>
    <span class="badge" id="b-count">__MIMI_FILECOUNT__ 项</span>
    <span class="badge ro" id="b-ro" style="display:none">只读</span>
  </div>
  <div class="sub">共享目录：<span id="b-root">__MIMI_ROOT__</span></div>
</header>
<main>
  <div class="bar">
    <span class="crumbs" id="crumbs"></span>
    <button id="b-up">上一级</button>
    <button id="b-refresh">刷新</button>
    <button id="b-mkdir">新建文件夹</button>
    <button class="pri" id="b-upload">上传文件</button>
    <input type="file" id="picker" multiple style="display:none">
  </div>
  <div id="drop">把文件拖到这里上传</div>
  <ul class="list" id="list"></ul>
  <div class="empty" id="empty" style="display:none">这个目录是空的</div>
</main>
<footer>通过密密文件管理（MimiFiles）无线分享 · 仅在同一局域网内可访问</footer>
<div id="pg"><span id="pgtxt">上传中…</span><div id="pgt"><div id="pgb"></div></div></div>
<div id="toast"></div>
<script>
var RO = __MIMI_READONLY__;
var cur = "";
function enc(s){return encodeURIComponent(s);}
function el(id){return document.getElementById(id);}
function toast(msg,err){
  var t=el('toast');t.textContent=msg;t.className=err?'on err':'on';
  setTimeout(function(){t.className=err?'err':'';},2600);
}
function human(n){
  if(n===undefined||n===null||n<0)return '';
  var u=['B','KB','MB','GB','TB'],i=0;var v=n;
  while(v>=1024&&i<u.length-1){v/=1024;i++;}
  return (i===0?v:v.toFixed(v<10?1:0))+' '+u[i];
}
function api(path,qs,opt){
  var url=path+(qs?('?'+qs):'');
  return fetch(url,opt||{}).then(function(r){
    if(r.status===401){throw new Error('需要登录，请刷新页面重新输入账号密码');}
    if(!r.ok){return r.text().then(function(t){throw new Error(t||('HTTP '+r.status));});}
    return r.json();
  });
}
function crumbs(){
  var parts=cur?cur.split('/'):[];
  var html='<a data-go="">根目录</a>';
  var acc='';
  for(var i=0;i<parts.length;i++){
    acc=acc?(acc+'/'+parts[i]):parts[i];
    html+=' / <a data-go="'+acc.replace(/"/g,'&quot;')+'">'+esc(parts[i])+'</a>';
  }
  el('crumbs').innerHTML=html;
  var as=el('crumbs').getElementsByTagName('a');
  for(var j=0;j<as.length;j++){
    as[j].onclick=function(){cur=this.getAttribute('data-go');load();};
  }
}
function esc(s){
  return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
}
function icon(n,isDir){
  if(isDir)return '📁';
  var e=n.split('.').pop().toLowerCase();
  if(['jpg','jpeg','png','gif','webp','bmp','heic','avif'].indexOf(e)>=0)return '🖼';
  if(['mp4','mkv','mov','avi','webm','3gp','m4v'].indexOf(e)>=0)return '🎬';
  if(['mp3','flac','wav','m4a','aac','ogg'].indexOf(e)>=0)return '🎵';
  if(e==='pdf')return '📕';
  if(['txt','log','md','json','xml','csv','ini','kt','java','js','py'].indexOf(e)>=0)return '📄';
  if(['zip','rar','7z','tar','gz','xz','bz2'].indexOf(e)>=0)return '🗜';
  return '📄';
}
function render(items){
  var ul=el('list');ul.innerHTML='';
  el('empty').style.display=items.length?'none':'block';
  for(var i=0;i<items.length;i++){
    (function(it){
      var li=document.createElement('li');
      li.className='row'+(it.dir?' dir':'');
      var nm=document.createElement('div');nm.className='nm';
      var a=document.createElement('a');
      a.textContent=icon(it.name,it.dir)+'  '+it.name;
      if(it.dir){a.onclick=function(){cur=it.rel;load();};}
      else{a.href='/f/'+it.rel.split('/').map(enc).join('/');a.target='_blank';a.rel='noopener';}
      nm.appendChild(a);
      var mt=document.createElement('div');mt.className='meta';
      mt.textContent=it.dir?('文件夹'):human(it.size);
      var ac=document.createElement('div');ac.className='act';
      if(!it.dir){
        var d=document.createElement('button');d.textContent='下载';
        d.onclick=function(){location.href='/d/'+it.rel.split('/').map(enc).join('/');};
        ac.appendChild(d);
      }
      if(!RO){
        var r=document.createElement('button');r.textContent='重命名';
        r.onclick=function(){
          var nn=prompt('新名称',it.name);if(!nn||nn===it.name)return;
          api('/api/rename','path='+enc(it.rel)+'&name='+enc(nn),{method:'POST'})
            .then(function(){toast('已重命名');load();},function(e){toast(e.message,true);});
        };
        ac.appendChild(r);
        var x=document.createElement('button');x.textContent='删除';
        x.onclick=function(){
          if(!confirm('确定删除「'+it.name+'」'+(it.dir?'（含其中全部内容）':'')+'？'))return;
          api('/api/delete','path='+enc(it.rel)+'&recursive='+(it.dir?1:0),{method:'POST'})
            .then(function(){toast('已删除');load();},function(e){toast(e.message,true);});
        };
        ac.appendChild(x);
      }
      li.appendChild(nm);li.appendChild(mt);li.appendChild(ac);
      ul.appendChild(li);
    })(items[i]);
  }
}
function load(){
  crumbs();
  api('/api/list','path='+enc(cur)).then(function(res){
    cur=res.path;crumbs();render(res.entries);
    el('b-count').textContent=res.entries.length+' 项';
  },function(e){toast(e.message,true);});
  el('b-mkdir').style.display=RO?'none':'';
  el('b-upload').style.display=RO?'none':'';
  el('drop').style.display=RO?'none':'';
}
function upload(files){
  var arr=Array.prototype.slice.call(files);
  if(!arr.length)return;
  el('pg').style.display='block';
  var done=0;
  function next(){
    if(done>=arr.length){el('pg').style.display='none';el('pgb').style.width='0';
      toast('已上传 '+done+' 个文件');load();return;}
    var f=arr[done];
    var rel=cur?cur+'/'+f.name:f.name;
    var url='/f/'+rel.split('/').map(enc).join('/');
    el('pgtxt').textContent='上传中 ('+(done+1)+'/'+arr.length+')：'+f.name;
    var xhr=new XMLHttpRequest();
    xhr.open('PUT',url,true);
    if(f.type)xhr.setRequestHeader('Content-Type',f.type);
    xhr.upload.onprogress=function(e){
      if(e.lengthComputable){
        var base=done/arr.length,share=1/arr.length;
        el('pgb').style.width=Math.round((base+share*e.loaded/e.total)*100)+'%';
      }
    };
    xhr.onload=function(){
      if(xhr.status>=200&&xhr.status<300){done++;next();}
      else{el('pg').style.display='none';toast('上传失败：'+(xhr.responseText||xhr.status),true);}
    };
    xhr.onerror=function(){el('pg').style.display='none';toast('网络中断',true);};
    xhr.send(f);
  }
  next();
}
el('b-up').onclick=function(){if(!cur)return;var p=cur.split('/');p.pop();cur=p.join('/');load();};
el('b-refresh').onclick=load;
el('b-upload').onclick=function(){el('picker').click();};
el('picker').onchange=function(){upload(this.files);this.value='';};
el('b-mkdir').onclick=function(){
  var n=prompt('新建文件夹名称');if(!n)return;
  api('/api/mkdir','path='+enc(cur)+'&name='+enc(n),{method:'POST'})
    .then(function(){toast('已新建');load();},function(e){toast(e.message,true);});
};
var dz=el('drop');
['dragenter','dragover'].forEach(function(ev){dz.addEventListener(ev,function(e){e.preventDefault();dz.className='on';});});
['dragleave','drop'].forEach(function(ev){dz.addEventListener(ev,function(e){e.preventDefault();dz.className='';});});
dz.addEventListener('drop',function(e){
  if(e.dataTransfer&&e.dataTransfer.files){upload(e.dataTransfer.files);}
});
dz.onclick=function(){if(!RO)el('picker').click();};
if(RO){el('b-ro').style.display='';}
load();
</script>
</body>
</html>
"""
}
