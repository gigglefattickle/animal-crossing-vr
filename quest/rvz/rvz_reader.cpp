#include "rvz_reader.h"
#include <zstd.h>
#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
namespace {
static uint32_t be32(const uint8_t*p){return(uint32_t(p[0])<<24)|(uint32_t(p[1])<<16)|(uint32_t(p[2])<<8)|p[3];}
static uint64_t be64(const uint8_t*p){return(uint64_t(be32(p))<<32)|be32(p+4);}
static bool add_ok(uint64_t a,uint64_t b,uint64_t lim){return a<=lim&&b<=lim-a;}
struct Group{uint32_t offset4,data_size,packed_size;};
struct Cache{uint32_t index=UINT32_MAX;uint64_t age=0;std::vector<uint8_t> bytes;};
class Reader{
public:
 bool open(const char*path){
  file_.open(path,std::ios::binary);if(!file_)return false;
  file_.seekg(0,std::ios::end);auto end=file_.tellg();if(end<0x124)return false;file_size_=uint64_t(end);file_.seekg(0);
  std::array<uint8_t,72+0xDC> h{};if(!read_at(0,h.data(),h.size())||memcmp(h.data(),"RVZ\1",4))return false;
  uint32_t dh=be32(h.data()+12);iso_size_=be64(h.data()+36);if(dh<0xDC||dh>0x1000||!add_ok(72,dh,file_size_)||iso_size_<0x8000)return false;
  const uint8_t*d=h.data()+72;uint32_t type=be32(d),compression=be32(d+4);chunk_=be32(d+12);
  uint32_t partitions=be32(d+144),raw_count=be32(d+180);uint64_t raw_off=be64(d+184);uint32_t raw_sz=be32(d+192);groups_n_=be32(d+196);uint64_t table_off=be64(d+200);uint32_t table_sz=be32(d+208);
  if(type!=1||compression!=5||partitions||raw_count!=1||chunk_<0x8000||(chunk_&(chunk_-1))||!groups_n_||groups_n_>1000000||!raw_sz||!table_sz||!add_ok(raw_off,raw_sz,file_size_)||!add_ok(table_off,table_sz,file_size_))return false;
  std::vector<uint8_t> packed(raw_sz),raw;if(!read_at(raw_off,packed.data(),raw_sz)||!zdecode(packed.data(),packed.size(),24,raw))return false;
  data_off_=be64(raw.data());data_sz_=be64(raw.data()+8);if(be32(raw.data()+16)!=0||be32(raw.data()+20)!=groups_n_||!add_ok(data_off_,data_sz_,iso_size_))return false;
  if(((data_off_%chunk_)+data_sz_+chunk_-1)/chunk_!=groups_n_)return false;
  packed.resize(table_sz);if(!read_at(table_off,packed.data(),table_sz))return false;std::vector<uint8_t> table;
  if(!zdecode(packed.data(),packed.size(),size_t(groups_n_)*12,table))return false;
  groups_.resize(groups_n_);for(uint32_t i=0;i<groups_n_;++i){auto*p=table.data()+size_t(i)*12;groups_[i]={be32(p),be32(p+4),be32(p+8)};uint32_t n=groups_[i].data_size&0x7fffffffU;if(n&&!add_ok(uint64_t(groups_[i].offset4)*4,n,file_size_))return false;}
  memcpy(header_.data(),d+16,header_.size());return true;
 }
 bool read(uint64_t off,void*dst,uint32_t n){
  std::lock_guard<std::mutex> lock(mutex_);if(!dst||off>iso_size_||n>iso_size_-off)return false;auto*out=(uint8_t*)dst;
  while(n){if(off<header_.size()){uint32_t k=std::min<uint32_t>(n,uint32_t(header_.size()-off));memcpy(out,header_.data()+off,k);off+=k;out+=k;n-=k;continue;}
   if(off<data_off_||off>=data_off_+data_sz_)return false;uint64_t aligned=(data_off_/chunk_)*chunk_;uint64_t gi=(off-aligned)/chunk_;if(gi>=groups_.size())return false;Cache*c=get(uint32_t(gi));if(!c)return false;
   uint64_t base=aligned+gi*chunk_;uint32_t within=uint32_t(off-base);if(within>=c->bytes.size())return false;uint32_t k=std::min<uint32_t>(n,uint32_t(c->bytes.size()-within));memcpy(out,c->bytes.data()+within,k);off+=k;out+=k;n-=k;
  }return true;
 }
 uint64_t size()const{return iso_size_;}
private:
 bool read_at(uint64_t off,void*d,size_t n){if(!add_ok(off,n,file_size_))return false;file_.clear();file_.seekg(std::streamoff(off));return bool(file_.read((char*)d,std::streamsize(n)));}
 static bool zdecode(const uint8_t*s,size_t n,size_t want,std::vector<uint8_t>&o){o.resize(want);size_t got=ZSTD_decompress(o.data(),o.size(),s,n);return !ZSTD_isError(got)&&got==want;}
 static bool unpack(const std::vector<uint8_t>&in,size_t want,uint64_t disc,std::vector<uint8_t>&out){
  size_t p=0;out.clear();out.reserve(want);while(p<in.size()){if(in.size()-p<4)return false;uint32_t z=be32(in.data()+p);p+=4;bool random=(z&0x80000000U)!=0;z&=0x7fffffffU;if(z>want-out.size())return false;
   if(random){if(in.size()-p<68)return false;std::array<uint32_t,521>s{};for(int i=0;i<17;i++)s[i]=be32(in.data()+p+i*4);p+=68;for(int i=17;i<521;i++)s[i]=(s[i-17]<<23)^(s[i-16]>>9)^s[i-1];auto advance=[&](){for(int i=0;i<32;i++)s[i]^=s[i+489];for(int i=32;i<521;i++)s[i]^=s[i-32];};for(int i=0;i<4;i++)advance();uint64_t skip=(disc+out.size())%0x8000,gen=0;size_t w=0;while(gen<skip+z){if(w==521){advance();w=0;}uint32_t v=s[w++];uint8_t b[4]={uint8_t(v>>24),uint8_t(v>>18),uint8_t(v>>8),uint8_t(v)};for(int j=0;j<4&&gen<skip+z;j++,gen++)if(gen>=skip)out.push_back(b[j]);}
   }else{if(in.size()-p<z)return false;out.insert(out.end(),in.begin()+p,in.begin()+p+z);p+=z;}
  }return out.size()==want;
 }
 Cache*get(uint32_t i){for(auto&c:cache_)if(c.index==i){c.age=++clock_;return &c;}Cache*v=&cache_[0];for(auto&c:cache_)if(c.index==UINT32_MAX||c.age<v->age)v=&c;
  auto&g=groups_[i];uint32_t stored=g.data_size&0x7fffffffU;bool compressed=(g.data_size&0x80000000U)!=0;uint64_t remain=(data_off_%chunk_)+data_sz_-uint64_t(i)*chunk_;if(!remain)return nullptr;uint64_t rounded=((remain+0x7fff)/0x8000)*0x8000;size_t expected=size_t(std::min<uint64_t>(chunk_,rounded));if(!expected||expected>chunk_)return nullptr;v->bytes.clear();
  if(!stored)v->bytes.assign(expected,0);else{std::vector<uint8_t> src(stored);if(!read_at(uint64_t(g.offset4)*4,src.data(),stored))return nullptr;size_t mid_sz=g.packed_size?g.packed_size:expected;std::vector<uint8_t> mid;if(compressed){if(!zdecode(src.data(),src.size(),mid_sz,mid))return nullptr;}else{if(src.size()!=mid_sz)return nullptr;mid.swap(src);}
   if(g.packed_size){uint64_t base=(data_off_/chunk_)*chunk_+uint64_t(i)*chunk_;if(!unpack(mid,expected,base,v->bytes))return nullptr;}else{if(mid.size()!=expected)return nullptr;v->bytes.swap(mid);}
  }v->index=i;v->age=++clock_;return v;
 }
 std::ifstream file_;uint64_t file_size_=0,iso_size_=0,data_off_=0,data_sz_=0;uint32_t chunk_=0,groups_n_=0;std::array<uint8_t,0x80>header_{};std::vector<Group>groups_;std::array<Cache,3>cache_{};uint64_t clock_=0;std::mutex mutex_;
};
}
extern "C" void*acvr_rvz_open(const char*p){if(!p||!*p)return nullptr;std::unique_ptr<Reader>r(new Reader);if(!r->open(p))return nullptr;return r.release();}
extern "C" int acvr_rvz_read(void*h,uint64_t o,void*d,uint32_t n){return h&&static_cast<Reader*>(h)->read(o,d,n)?1:0;}
extern "C" uint64_t acvr_rvz_size(void*h){return h?static_cast<Reader*>(h)->size():0;}
extern "C" void acvr_rvz_close(void*h){delete static_cast<Reader*>(h);}
