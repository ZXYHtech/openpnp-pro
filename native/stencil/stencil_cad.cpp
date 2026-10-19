// OpenPnP Pro stencil CAD worker. No Python, JVM embedding or network service.
// SPDX-License-Identifier: GPL-3.0-or-later
#include "third_party/json.hpp"
#include <BRepPrimAPI_MakeBox.hxx>
#include <BRepPrimAPI_MakeCylinder.hxx>
#include <BRepPrimAPI_MakePrism.hxx>
#include <BRepBuilderAPI_MakeEdge.hxx>
#include <BRepBuilderAPI_MakeWire.hxx>
#include <BRepBuilderAPI_MakeFace.hxx>
#include <BRepBuilderAPI_Transform.hxx>
#include <BRepAlgoAPI_Fuse.hxx>
#include <BRepAlgoAPI_Cut.hxx>
#include <BRepAlgoAPI_Common.hxx>
#include <BRepCheck_Analyzer.hxx>
#include <BRepGProp.hxx>
#include <BRepBndLib.hxx>
#include <BRepMesh_IncrementalMesh.hxx>
#include <BRep_Tool.hxx>
#include <BRep_Builder.hxx>
#include <GC_MakeArcOfCircle.hxx>
#include <GProp_GProps.hxx>
#include <Bnd_Box.hxx>
#include <STEPControl_Writer.hxx>
#include <STEPControl_Reader.hxx>
#include <StlAPI_Writer.hxx>
#include <TopExp_Explorer.hxx>
#include <TopoDS.hxx>
#include <TopoDS_Compound.hxx>
#include <Poly_Triangulation.hxx>
#include <Interface_Static.hxx>
#include <fstream>
#include <filesystem>
#include <iostream>
#include <cmath>
#include <vector>
#include <stdexcept>
using json = nlohmann::json;
using namespace std;
static gp_Pnt point(const json& p){return gp_Pnt(p[0],p[1],p[2]);}
static double volume(const TopoDS_Shape& s){GProp_GProps g; BRepGProp::VolumeProperties(s,g); return abs(g.Mass());}
static int solids(const TopoDS_Shape& s){int n=0; for(TopExp_Explorer e(s,TopAbs_SOLID);e.More();e.Next())n++;return n;}
static TopoDS_Shape compound(const vector<TopoDS_Shape>& s){BRep_Builder b;TopoDS_Compound c;b.MakeCompound(c);for(auto& x:s)b.Add(c,x);return c;}
static TopoDS_Shape booleanOp(const string& op,const TopoDS_Shape& a,const TopoDS_Shape& b){
 if(op=="union"){BRepAlgoAPI_Fuse x(a,b); x.Build();if(!x.IsDone()||x.HasErrors())throw runtime_error("union failed");return x.Shape();}
 if(op=="cut"){BRepAlgoAPI_Cut x(a,b);x.Build();if(!x.IsDone()||x.HasErrors())throw runtime_error("cut failed");return x.Shape();}
 BRepAlgoAPI_Common x(a,b);x.Build();if(!x.IsDone()||x.HasErrors())throw runtime_error("intersection failed");return x.Shape();
}
static TopoDS_Shape rounded(const json& o){
 double x=o["x"],y=o["y"],z=o["z"],w=o["w"],h=o["h"],d=o["d"],r=o["r"];
 double l=x-w/2,rr=x+w/2,b=y-h/2,t=y+h/2,k=1-sqrt(.5);
 BRepBuilderAPI_MakeWire wire;
 auto line=[&](double a,double c,double e,double f){wire.Add(BRepBuilderAPI_MakeEdge(gp_Pnt(a,c,z),gp_Pnt(e,f,z)).Edge());};
 auto arc=[&](double a,double c,double e,double f,double g,double h){wire.Add(BRepBuilderAPI_MakeEdge(GC_MakeArcOfCircle(gp_Pnt(a,c,z),gp_Pnt(e,f,z),gp_Pnt(g,h,z)).Value()).Edge());};
 line(l+r,b,rr-r,b);arc(rr-r,b,rr-k*r,b+k*r,rr,b+r);
 line(rr,b+r,rr,t-r);arc(rr,t-r,rr-k*r,t-k*r,rr-r,t);
 line(rr-r,t,l+r,t);arc(l+r,t,l+k*r,t-k*r,l,t-r);
 line(l,t-r,l,b+r);arc(l,b+r,l+k*r,b+k*r,l+r,b);
 return BRepPrimAPI_MakePrism(BRepBuilderAPI_MakeFace(wire.Wire()).Face(),gp_Vec(0,0,d)).Shape();
}
static double area(const json& p){double a=0;for(size_t i=0;i<p.size();i++){auto q=p[(i+1)%p.size()];a+=double(p[i][0])*double(q[1])-double(q[0])*double(p[i][1]);}return a/2;}
static bool contains(const json& p,double x,double y){bool in=false;for(size_t i=0,j=p.size()-1;i<p.size();j=i++){double xi=p[i][0],yi=p[i][1],xj=p[j][0],yj=p[j][1];if(((yi>y)!=(yj>y))&&(x<(xj-xi)*(y-yi)/(yj-yi)+xi))in=!in;}return in;}
static TopoDS_Wire polywire(json p,double z,bool positive){if((area(p)>0)!=positive)reverse(p.begin(),p.end());BRepBuilderAPI_MakeWire w;for(size_t i=0;i<p.size();i++){auto a=p[i],b=p[(i+1)%p.size()];w.Add(BRepBuilderAPI_MakeEdge(gp_Pnt(a[0],a[1],z),gp_Pnt(b[0],b[1],z)).Edge());}return w.Wire();}
static TopoDS_Shape profile(const json& o){
 auto ps=o["contours"];vector<int> depth(ps.size());
 for(size_t i=0;i<ps.size();i++)for(size_t j=0;j<ps.size();j++)if(i!=j&&contains(ps[j],ps[i][0][0],ps[i][0][1]))depth[i]++;
 vector<TopoDS_Shape> result;
 for(size_t i=0;i<ps.size();i++)if(depth[i]%2==0){BRepBuilderAPI_MakeFace f(polywire(ps[i],o["z"],true));for(size_t j=0;j<ps.size();j++)if(depth[j]==depth[i]+1&&contains(ps[i],ps[j][0][0],ps[j][0][1]))f.Add(polywire(ps[j],o["z"],false));result.push_back(BRepPrimAPI_MakePrism(f.Face(),gp_Vec(0,0,double(o["d"]))).Shape());}
 return compound(result);
}
static json metrics(const TopoDS_Shape& s){Bnd_Box b;BRepBndLib::AddOptimal(s,b,false,false);double x,y,z,X,Y,Z;b.Get(x,y,z,X,Y,Z);return {{"volume_mm3",volume(s)},{"solids",solids(s)},{"valid",BRepCheck_Analyzer(s).IsValid()},{"min",{x,y,z}},{"max",{X,Y,Z}},{"bbox_mm",{X-x,Y-y,Z-z}}};}
static void meshJson(const TopoDS_Shape& s,const string& name){
 ofstream f(name,ios::binary); f.precision(10); f<<"{\"triangles\":[";bool first=true;
 for(TopExp_Explorer e(s,TopAbs_FACE);e.More();e.Next()){auto face=TopoDS::Face(e.Current());TopLoc_Location loc;auto tri=BRep_Tool::Triangulation(face,loc);if(tri.IsNull())continue;for(int i=1;i<=tri->NbTriangles();i++){int a,b,c;tri->Triangle(i).Get(a,b,c);if(face.Orientation()==TopAbs_REVERSED)swap(b,c);if(!first)f<<",";first=false;f<<"[";int ns[]={a,b,c};for(int j=0;j<3;j++){auto p=tri->Node(ns[j]).Transformed(loc.Transformation());if(j)f<<",";f<<p.X()<<","<<p.Y()<<","<<p.Z();}f<<"]";}}
 f<<"]}";
}
int main(int argc,char** argv){try{
 if(argc==2&&string(argv[1])=="--version"){cout<<"OpenPnP-Stencil-CAD/1 OCCT/8.0.1 no-python"<<endl;return 0;}
 ifstream in("cad-input.json");json task;in>>task;if(task.at("protocol")!=1)throw runtime_error("unsupported CAD protocol");
 vector<TopoDS_Shape> shapes;int index=0;
 for(auto& o:task.at("ops")){string t=o.at("type");TopoDS_Shape s;
 if(t=="box")s=BRepPrimAPI_MakeBox(gp_Pnt(double(o["x"])-double(o["w"])/2,double(o["y"])-double(o["h"])/2,o["z"]),o["w"],o["h"],o["d"]).Shape();
 else if(t=="rounded")s=rounded(o);
 else if(t=="cylinder")s=BRepPrimAPI_MakeCylinder(gp_Ax2(point(o["origin"]),gp_Dir(double(o["axis"][0]),double(o["axis"][1]),double(o["axis"][2]))),o["r"],o["d"]).Shape();
 else if(t=="profile")s=profile(o);
 else if(t=="transform"){gp_Trsf r,tr;double angle=o.value("angle",0.0);auto ax=o.value("axis",json::array({1,0,0}));r.SetRotation(gp_Ax1(point(o.value("origin",json::array({0,0,0}))),gp_Dir(double(ax[0]),double(ax[1]),double(ax[2]))),angle*acos(-1)/180);auto v=o.value("move",json::array({0,0,0}));tr.SetTranslation(gp_Vec(v[0],v[1],v[2]));s=BRepBuilderAPI_Transform(shapes.at(o.at("ref").get<int>()),tr*r,true).Shape();}
 else if(t=="compound"){vector<TopoDS_Shape> ss;for(auto& id:o["refs"])ss.push_back(shapes.at(id.get<int>()));s=compound(ss);}
 else {auto ids=o.at("refs");s=shapes.at(ids[0].get<int>());for(size_t i=1;i<ids.size();i++)s=booleanOp(t,s,shapes.at(ids[i].get<int>()));}
 if(s.IsNull())throw runtime_error("empty CAD operation "+to_string(index));shapes.push_back(s);if(index++%20==0)cout<<"BUILD "<<index<<"/"<<task["ops"].size()<<endl;
 }
 json report={{"kernel","OCCT 8.0.1"},{"protocol",1},{"parts",json::object()},{"checks",json::array()},{"measurements",json::object()}};
 for(auto& m:task.value("measurements",json::array()))report["measurements"][m["name"].get<string>()]=metrics(shapes.at(m["ref"].get<int>()));
 for(auto& c:task.value("checks",json::array())){auto a=shapes.at(c["a"].get<int>()),b=shapes.at(c["b"].get<int>());double v=volume(booleanOp("common",a,b)),err=c.value("full",false)?abs(volume(b)-v):v;double tolerance=c.value("tolerance",1e-5);report["checks"].push_back({{"label",c["label"]},{"intersection_mm3",v},{"error",err},{"passed",err<=tolerance}});if(err>tolerance)throw runtime_error(c["label"].get<string>()+": "+to_string(err));}
 for(auto& e:task.at("exports")){auto s=shapes.at(e["ref"].get<int>());string name=e["name"];auto m=metrics(s);if(!m["valid"].get<bool>()||m["solids"]!=e["solids"]||double(m["volume_mm3"])<=0)throw runtime_error("invalid solid: "+name);
 if(e.value("print",false)&&abs(double(m["min"][2]))>1e-5)throw runtime_error("print bed is not Z=0: "+name);
 STEPControl_Writer writer;Interface_Static::SetCVal("write.step.schema","AP214IS");writer.Transfer(s,STEPControl_AsIs);if(writer.Write((name+".step").c_str())!=IFSelect_RetDone)throw runtime_error("STEP write failed");STEPControl_Reader reader;if(reader.ReadFile((name+".step").c_str())!=IFSelect_RetDone)throw runtime_error("STEP read failed");reader.TransferRoots();auto restored=reader.OneShape();if(!BRepCheck_Analyzer(restored).IsValid()||solids(restored)!=solids(s)||abs(volume(restored)-volume(s))>max(.001,volume(s)*1e-7))throw runtime_error("STEP roundtrip failed: "+name);m["step_roundtrip"]=true;
 if(e.value("mesh",true)){BRepMesh_IncrementalMesh mesh(s,.005,false,.05,true);StlAPI_Writer stl;stl.ASCIIMode()=false;if(!stl.Write(s,(name+".stl").c_str()))throw runtime_error("STL write failed");meshJson(s,name+".mesh.json");}
 report["parts"][name]=m;cout<<"EXPORT "<<name<<endl;
 }
 ofstream out("cad-report.json");out<<report.dump(2);return 0;
 }catch(const Standard_Failure& e){cerr<<"CAD: "<<e.GetMessageString()<<endl;return 2;}catch(const exception& e){cerr<<e.what()<<endl;return 2;}}
