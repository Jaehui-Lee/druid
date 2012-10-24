package com.metamx.druid.aggregation.post;

import com.google.common.collect.Maps;
import com.metamx.common.IAE;
import org.codehaus.jackson.annotate.JsonCreator;
import org.codehaus.jackson.annotate.JsonProperty;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 */
public class ArithmeticPostAggregator implements PostAggregator
{
  private static final Comparator COMPARATOR = new Comparator()
  {
    @Override
    public int compare(Object o, Object o1)
    {
      return ((Double) o).compareTo((Double) o1);
    }
  };

  private final String name;
  private final String fnName;
  private final List<PostAggregator> fields;
  private final Ops op;

  @JsonCreator
  public ArithmeticPostAggregator(
      @JsonProperty("name") String name,
      @JsonProperty("fn") String fnName,
      @JsonProperty("fields") List<PostAggregator> fields
  )
  {
    this.name = name;
    this.fnName = fnName;
    this.fields = fields;
    if (fields.size() <= 1) {
      throw new IAE("Illegal number of fields[%s], must be > 1", fields.size());
    }

    this.op = Ops.lookup(fnName);
    if (op == null) {
      throw new IAE("Unknown operation[%s], known operations[%s]", fnName, Ops.getFns());
    }
  }

  @Override
  public Comparator getComparator()
  {
    return COMPARATOR;
  }

  @Override
  public Object compute(Map<String, Object> values)
  {
    Iterator<PostAggregator> fieldsIter = fields.iterator();
    double retVal = ((Number) fieldsIter.next().compute(values)).doubleValue();
    while (fieldsIter.hasNext()) {
      retVal = op.compute(retVal, ((Number) fieldsIter.next().compute(values)).doubleValue());
    }

    return retVal;
  }

  @Override
  @JsonProperty
  public String getName()
  {
    return name;
  }

  @JsonProperty("fn")
  public String getFnName()
  {
    return fnName;
  }

  @JsonProperty
  public List<PostAggregator> getFields()
  {
    return fields;
  }

  @Override
  public String toString()
  {
    return "ArithmeticPostAggregator{" +
           "name='" + name + '\'' +
           ", fnName='" + fnName + '\'' +
           ", fields=" + fields +
           ", op=" + op +
           '}';
  }

  private static enum Ops
  {
    PLUS("+") {
          double compute(double lhs, double rhs) {
            return lhs + rhs;
          }
    },
    MINUS("-"){
          double compute(double lhs, double rhs) {
            return lhs - rhs;
          }
    },
    MULT("*"){
          double compute(double lhs, double rhs) {
            return lhs * rhs;
          }
    },
    DIV("/"){
          double compute(double lhs, double rhs) {
            return (rhs == 0.0) ? 0 : (lhs / rhs);
          }
    };

    private static final Map<String, Ops> lookupMap = Maps.newHashMap();

    static {
      for (Ops op : Ops.values()) {
        lookupMap.put(op.getFn(), op);
      }
    }

    private final String fn;

    Ops(String fn)
    {
      this.fn = fn;
    }

    public String getFn()
    {
      return fn;
    }

    abstract double compute(double lhs, double rhs);

    static Ops lookup(String fn)
    {
      return lookupMap.get(fn);
    }

    static Set<String> getFns()
    {
      return lookupMap.keySet();
    }
  }
}
