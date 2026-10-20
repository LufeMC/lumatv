const { chromium }=require('playwright');
const fs=require('fs');const path=require('path');
process.chdir(path.resolve(__dirname,'..'));
(async()=>{
 const browser=await chromium.launch({headless:true});
 const output=path.resolve(process.argv[2]||'out/phone-review');fs.mkdirSync(output,{recursive:true});
 for(const locale of ['en-US','pt-BR']){
  const context=await browser.newContext({viewport:{width:390,height:844},deviceScaleFactor:1,locale});
  const page=await context.newPage();
  await page.route('**/*',async route=>{
   const u=new URL(route.request().url());
   if(u.pathname==='/s'||u.pathname==='/t')return route.fulfill({contentType:'text/html',body:fs.readFileSync('app/assets/phone/'+(u.pathname==='/s'?'setup':'typing')+'.html','utf8').replaceAll('{{TV}}','Bedroom TV')});
   if(u.pathname==='/api/setup')return route.fulfill({contentType:'application/json',body:JSON.stringify({hasKey:true,city:'San Francisco',region:'US',temperature:'C'})});
   if(u.pathname==='/api/type')return route.fulfill({contentType:'application/json',body:JSON.stringify({results:[{id:'tv:48891',title:'Brooklyn Nine-Nine',label:'Brooklyn Nine-Nine',year:'2013',kind:'series'}]})});
   return route.fulfill({status:404,body:''});
  });
  for(const view of ['s','t']){
   await page.goto('http://lumatv.local/'+view+'?k=design-fixture');
   if(view==='t'){await page.locator('input').fill('brooklyn');await page.waitForTimeout(250);}
   await page.screenshot({path:path.join(output,(view==='s'?'setup':'typing')+'-'+locale+'.png'),fullPage:true});
   if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth))throw new Error('Horizontal overflow in '+view+' '+locale);
   console.log('Reviewed phone '+view+' in '+locale+'.');
  }
  await context.close();
 }
 await browser.close();
})();
