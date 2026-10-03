# GCC Makefile
CXX = g++
CXXFLAGS = -std=c++11 -ggdb -I../../../../angelscript/include
SRCDIR = ../../source
OBJDIR = obj

SRCNAMES = \
  main.cpp \
  vas_project.cpp

OBJ = $(addprefix $(OBJDIR)/, $(notdir $(SRCNAMES:.cpp=.o))) obj/scriptbuilder.o obj/scripthelper.o
BIN = ../../bin/asbuild
DELETER = rm -f

all: $(BIN)

$(BIN): $(OBJ)
	$(CXX) -o $(BIN) $(OBJ) -static -langelscript -dynamic -lpthread -L ../../../../angelscript/lib
	@echo -------------------------------------------------------------------
	@echo Done.

$(OBJDIR)/main.o: $(SRCDIR)/vas_project.h
$(OBJDIR)/vas_project.o: $(SRCDIR)/vas_project.h ../../vendor/nlohmann/json.hpp

$(OBJDIR)/%.o: $(SRCDIR)/%.cpp
	$(CXX) $(CXXFLAGS) -o $@ -c $<

obj/scripthelper.o: ../../../../add_on/scripthelper/scripthelper.cpp
	$(CXX) $(CXXFLAGS) -o $@ -c $<

obj/scriptbuilder.o: ../../../../add_on/scriptbuilder/scriptbuilder.cpp
	$(CXX) $(CXXFLAGS) -o $@ -c $<

clean:
	$(DELETER) $(OBJ) $(BIN)

.PHONY: all clean
