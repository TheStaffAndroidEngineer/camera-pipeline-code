"""Independent protocol histories; isolated process, temporary database, ephemeral HTTP."""
import concurrent.futures
import hashlib
import http.client
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
import uuid
from http.server import ThreadingHTTPServer
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'server'))
from base import Problem, handler
from service import Service

class Review(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.database=Path(self.temp.name)/'state.sqlite'
  self.a=Service(self.database);self.b=Service(self.database);self.servers=[]
 def tearDown(self):
  for server,thread in self.servers:server.shutdown();server.server_close();thread.join(2)
  self.temp.cleanup()
 def body(self,action,args=None,identity=None):return {'id':identity or str(uuid.uuid4()),'action':action,'args':args or {}}
 def run_command(self,body,owner=None):return (owner or self.a).route('POST','/commands',body)[1]
 def command(self,action,args=None,owner=None):return self.run_command(self.body(action,args),owner)
 def view(self,owner=None):return (owner or self.a).route('GET','/view',{})[1]['data']
 def serve(self):
  server=ThreadingHTTPServer(('127.0.0.1',0),handler(self.a));thread=threading.Thread(target=lambda:server.serve_forever(poll_interval=.01),daemon=True);thread.start();self.servers.append((server,thread));return server.server_port
 def http(self,port,path,body=None):
  c=http.client.HTTPConnection('127.0.0.1',port,timeout=4)
  try:
   c.request('GET' if body is None else 'POST',path,body=None if body is None else json.dumps(body));r=c.getresponse();return r.status,json.loads(r.read())
  finally:c.close()


 def asset(self,key=None):return {'assetId':key or str(uuid.uuid4()),'width':640,'height':480,'sha256':hashlib.sha256(b'metadata-fixture-only').hexdigest()}
 def register(self):args=self.asset();self.command('register',args);return args
 def row(self,key):return next(r for r in self.view()['jobs'] if r['assetId']==key)
 def test_registration_contains_metadata_only_and_same_identity_is_coalesced(self):
  args=self.asset()
  with concurrent.futures.ThreadPoolExecutor(2) as pool:results=list(pool.map(lambda owner:self.command('register',args,owner)['outcome']['accepted'],[self.a,self.b]))
  self.assertEqual([True,True],results);self.assertEqual(1,len(self.view()['jobs']))
  self.assertEqual(set(args)|{'generation','status'},set(self.row(args['assetId'])))
  with self.assertRaises(Problem):self.command('register',args|{'imageBytes':'private-content'})
 def test_changed_asset_payload_never_reuses_accepted_identity(self):
  args=self.register();before=self.view()
  for changed in (args|{'width':641},args|{'height':481},args|{'sha256':'0'*64}):
   with self.assertRaises(Problem):self.command('register',changed)
  self.assertEqual(before,self.view())
 def test_cancel_retry_new_generation_fences_old_completion(self):
  args=self.register();old={'assetId':args['assetId'],'generation':1}
  self.command('cancel',old);self.assertFalse(self.command('finish',old)['outcome']['accepted']);self.command('retry',old)
  self.assertEqual((2,'QUEUED'),(self.row(args['assetId'])['generation'],self.row(args['assetId'])['status']))
  for action in ('finish','cancel','retry'):self.assertFalse(self.command(action,old)['outcome']['accepted'])
  self.command('finish',old|{'generation':2});self.assertEqual('COMPLETE',self.row(args['assetId'])['status'])
 def test_old_registration_receipt_cannot_reset_cancelled_or_retried_state(self):
  args=self.asset();body=self.body('register',args);receipt=self.run_command(body);grant={'assetId':args['assetId'],'generation':1}
  self.command('cancel',grant);self.assertEqual(receipt,self.run_command(body,Service(self.database)));self.assertEqual('CANCELLED',self.row(args['assetId'])['status'])
  self.command('register',args);self.assertEqual('CANCELLED',self.row(args['assetId'])['status']);self.command('retry',grant)
  self.assertEqual(receipt,self.run_command(body));self.assertEqual(2,self.row(args['assetId'])['generation'])
 def test_finish_cancel_race_has_one_terminal_result(self):
  args=self.register();grant={'assetId':args['assetId'],'generation':1}
  with concurrent.futures.ThreadPoolExecutor(2) as pool:results=list(pool.map(lambda pair:self.command(pair[1],grant,pair[0])['outcome']['accepted'],[(self.a,'finish'),(self.b,'cancel')]))
  self.assertEqual([False,True],sorted(results));self.assertIn(self.row(args['assetId'])['status'],('COMPLETE','CANCELLED'))
 def test_lost_registration_reply_restores_one_job(self):
  args=self.asset();body=self.body('register',args);port=self.serve();self.http(port,'/__control',{'drop_reply':True})
  with self.assertRaises(http.client.RemoteDisconnected):self.http(port,'/commands',body)
  self.http(port,'/__control',{});receipt=self.run_command(body,Service(self.database));self.assertTrue(receipt['outcome']['accepted']);self.assertEqual(1,len(self.view()['jobs']))
 def test_twenty_job_bound_has_durable_refusal(self):
  for _ in range(20):self.register()
  body=self.body('register',self.asset());refusal=self.run_command(body);self.assertFalse(refusal['outcome']['accepted']);self.assertEqual(refusal,self.run_command(body,self.b));self.assertEqual(20,len(self.view()['jobs']))
 def test_invalid_metadata_and_grants_do_not_mutate(self):
  for change in ({'width':0},{'height':10001},{'width':True},{'sha256':'broken'},{'assetId':'not-a-uuid'}):
   with self.assertRaises(Problem):self.command('register',self.asset()|change)
  self.assertEqual([],self.view()['jobs']);args=self.register()
  with self.assertRaises(Problem):self.command('finish',{'assetId':args['assetId'],'generation':True})
  self.assertEqual('QUEUED',self.row(args['assetId'])['status'])

if __name__=='__main__':unittest.main()
