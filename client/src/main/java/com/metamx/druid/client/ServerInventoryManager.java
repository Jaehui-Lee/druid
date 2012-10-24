package com.metamx.druid.client;

import com.google.common.collect.Maps;
import com.metamx.common.Pair;
import com.metamx.common.logger.Logger;
import com.metamx.phonebook.PhoneBook;
import com.metamx.phonebook.PhoneBookPeon;

import java.util.Iterator;
import java.util.Map;

/**
 */
public class ServerInventoryManager extends InventoryManager<DruidServer>
{
  private static final Map<String, Integer> removedSegments = Maps.newHashMap();

  public ServerInventoryManager(
      ServerInventoryManagerConfig config,
      PhoneBook yp
  )
  {
    super(
        new Logger(ServerInventoryManager.class.getName() + "." + config.getServerInventoryPath()),
        new InventoryManagerConfig(
            config.getServerIdPath(),
            config.getServerInventoryPath()
        ),
        yp,
        new ServerInventoryManagementStrategy(
            new Logger(
                ServerInventoryManager.class.getName() + "." + config.getServerInventoryPath()
            ),
            config.getRemovedSegmentLifetime()
        )
    );
  }

  private static class ServerInventoryManagementStrategy implements InventoryManagementStrategy<DruidServer>
  {
    private final Logger log;
    private final int segmentLifetime;

    ServerInventoryManagementStrategy(Logger log, int segmentLifetime)
    {
      this.log = log;
      this.segmentLifetime = segmentLifetime;
    }

    @Override
    public Class<DruidServer> getContainerClass()
    {
      return DruidServer.class;
    }

    @Override
    public Pair<String, PhoneBookPeon<?>> makeSubListener(final DruidServer druidServer)
    {
      return new Pair<String, PhoneBookPeon<?>>(
          druidServer.getName(),
          new PhoneBookPeon<DataSegment>()
          {
            @Override
            public Class<DataSegment> getObjectClazz()
            {
              return DataSegment.class;
            }

            @Override
            public void newEntry(String name, DataSegment segment)
            {
              log.info("Server[%s] added new DataSegment[%s]", druidServer.getName(), segment);
              druidServer.addDataSegment(name, segment);
            }

            @Override
            public void entryRemoved(String name)
            {
              log.info("Entry [%s] deleted", name);
              removedSegments.put(druidServer.getSegment(name).getIdentifier(), segmentLifetime);
              druidServer.removeDataSegment(name);
              log.info("Server[%s] removed dataSegment[%s]", druidServer.getName(), name);
            }
          }
      );
    }

    @Override
    public void objectRemoved(DruidServer baseObject)
    {
    }

    @Override
    public boolean doesSerde()
    {
      return false;
    }

    @Override
    public DruidServer deserialize(String name, Map<String, String> properties)
    {
      throw new UnsupportedOperationException();
    }
  }

  public int lookupSegmentLifetime(DataSegment segment)
  {
    Integer lifetime = removedSegments.get(segment.getIdentifier());
    return (lifetime == null) ? 0 : lifetime;
  }

  public void decrementRemovedSegmentsLifetime()
  {
    for (Iterator<Map.Entry<String, Integer>> mapIter = removedSegments.entrySet().iterator(); mapIter.hasNext(); ) {
      Map.Entry<String, Integer> segment = mapIter.next();
      int lifetime = segment.getValue() - 1;

      if (lifetime < 0) {
        mapIter.remove();
      } else {
        segment.setValue(lifetime);
      }
    }
  }
}
